-- EXCERPT from Deck's party backend (Supabase / Postgres migration). Not the full schema.
--
-- The bug: the queue order used to be a FORMULA, key = arrival_index - net_votes * 1.5.
-- A like moves a song past its neighbour only if their keys are less than 1.5 apart, which
-- breaks as soon as there are holes in the arrival numbers (played, deleted, withdrawn songs):
--   1 2 3 4, like on 3 -> 1 3 2 4; like on 4: key 4 - 1.5 = 2.5 > 2, so 4 does not move.
--
-- The fix: the order of the songs the host has not pinned is STORED (free_rank), and a vote
-- moves its song by exactly as many places as the guest's vote changed:
--   none -> like = 1 up, like -> dislike = 2 down, like withdrawn = 1 down.
--
-- Security model around it: guests have no account and no table write rights at all. They call
-- SECURITY DEFINER functions with a random guest token (only its SHA-256 hash is stored);
-- every function runs with an empty search_path and locks the party row first, so two votes
-- can never swap places at the same time.

-- Swap the song with its free neighbour, one place at a time. Pinned songs (host_rank) never
-- move by votes; free songs move around them (the queue view puts pinned songs in their slots).
create function private.move_free(p_party uuid, p_item uuid, p_steps integer) returns void
language plpgsql security definer set search_path = ''
as $$
declare
  v_rank integer; v_pinned boolean; v_neighbour_id uuid; v_neighbour_rank integer;
begin
  select free_rank, host_rank is not null into v_rank, v_pinned
  from public.queue_items where id = p_item and party_id = p_party and status = 'queued';
  if not found or v_pinned or p_steps = 0 then return; end if;

  for i in 1..abs(p_steps) loop
    if p_steps > 0 then            -- up: the closest free song above
      select id, free_rank into v_neighbour_id, v_neighbour_rank from public.queue_items
      where party_id = p_party and status = 'queued' and host_rank is null and free_rank < v_rank
      order by free_rank desc limit 1;
    else                           -- down: the closest free song below
      select id, free_rank into v_neighbour_id, v_neighbour_rank from public.queue_items
      where party_id = p_party and status = 'queued' and host_rank is null and free_rank > v_rank
      order by free_rank asc limit 1;
    end if;
    exit when not found;           -- already first / last
    update public.queue_items set free_rank = v_rank where id = v_neighbour_id;
    update public.queue_items set free_rank = v_neighbour_rank where id = p_item;
    v_rank := v_neighbour_rank;
  end loop;
end $$;

-- The guest's entry point (shortened): validate, store the vote, recount, move.
create or replace function public.guest_vote(p_party uuid, p_token text, p_item uuid, p_value integer)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
  v_party public.parties := private.lock_party(p_party);   -- SELECT ... FOR UPDATE on the party
  v_guest public.party_guests := private.find_guest(p_party, p_token);  -- by token hash
  v_old integer;
begin
  if v_party.id is null then return private.err('party_not_found'); end if;
  if v_guest.id is null then return private.err('guest_not_found'); end if;
  if p_value not in (-1, 0, 1) then return private.err('bad_value'); end if;
  -- (checks left out here: the item is queued in this party, and it is not the guest's own request)

  select coalesce((select value from public.votes where item_id = p_item and guest_id = v_guest.id), 0)
    into v_old;
  -- ... insert / update / delete the vote, then recount net_votes from the votes table ...

  perform private.move_free(p_party, p_item, p_value - v_old);   -- one place per changed vote
  return jsonb_build_object('ok', true, 'my_vote', p_value);
end $$;

-- API roles get nothing on the helper; guests reach it only through guest_vote.
revoke all on function private.move_free(uuid, uuid, integer) from public, anon, authenticated, service_role;
