> Cleaned-up version from Deck's private repository. Magyar változat: [party-design-hu.md](party-design-hu.md).
> No secrets, internal references or project identifiers.

# Party mode ("Aux"): the full design (Phase 6A + 6B)

> **Update in 1.2:** this is the design of the first party mode (1.0–1.1). Since 1.2 the party has no queue,
> likes or ranking of its own: a request goes into Deck's own "Play next" queue in arrival order, guests see
> Deck's "Up next" order, the only vote is 👎 (three take any song out), and a guest can move a song of the
> playing list forward. The security model, the limits and the cleanup below are unchanged. Why it changed:
> [README, decision 9](../README.md#9-party-mode-from-a-separate-queue-to-one-player).

Status: built and running (server, guest web page, Deck's host side).

## 1. In one sentence

The host starts a party in Deck, guests reach a web page through a QR code where they can request songs and
vote; the queue lives in a Postgres database, the server computes the order, and Deck starts the head of the
queue in YT Music about 1.5 s before the current song ends.

```
  Guest's phone (browser)                Supabase                             Host's phone
 ┌──────────────────────────┐      ┌─────────────────────────────┐      ┌──────────────────────┐
 │ party-web (Vercel)       │      │ Postgres                    │      │ Deck (6B)            │
 │  index.html + app.js     │ RPC  │  tables + RLS               │ RPC  │  party screen, QR    │
 │  - read: REST (SELECT)   │─────▶│  rules: SECURITY DEFINER    │◀─────│  host_* functions    │
 │  - write: guest_* RPC    │      │  functions                  │      │  with host_secret    │
 │  - live: Realtime (WS)   │◀─────│  Realtime (postgres_changes)│─────▶│  watches the queue   │
 │  - search: Edge Function │─────▶│ Edge Function: search ──────┼──▶ YouTube Data API       │
 └──────────────────────────┘      └─────────────────────────────┘      │  playFromUri → YT Music
                                                                        └──────────────────────┘
```

Three actors, three "keys":

| Who | Identifies with | Where the secret lives | What it is for |
|---|---|---|---|
| Anyone | publishable (anon) key | in the web page, public | reading + calling the RPCs (the function decides) |
| Guest | `guest_token` (random, given by the server on joining) | the browser's `localStorage` | requesting, voting, searching in their own name |
| Host | `host_secret` (random, given by the server when the party is created) | Deck's private storage | reordering, deleting, stepping, ending the party |
| Deck app | app key (create key) | compiled into the app, so treated as public | creating a new party; the quota is protected by project-wide caps (section 5) |

The database only keeps the SHA-256 hash of every secret. The secrets contain 244 bits of randomness
(two UUID v4), so salting and a slow hash (bcrypt) are not needed: there is nothing to guess from a dictionary.

## 2. Data model

Migrations: `supabase/migrations/`. Every table is in the `public` schema with RLS; the `private` schema is not exposed to the API.

| Table | Important columns | Readable by guests? |
|---|---|---|
| `parties` | `id` (uuid), `code` (4 letters, unique among active ones), `host_secret_hash`, `status` (active/ended), `now_playing` (jsonb), `settings` (jsonb), `search_count`, `next_arrival`, `created_at`, `ended_at` | yes, but not the `code`, `host_secret_hash`, `next_arrival` columns (column-level GRANT) |
| `party_guests` | `id`, `party_id`, `nickname`, `token_hash`, `search_count`, `last_request_at`, `created_at` | no (the nickname is copied onto the queue item) |
| `party_tracks` | `party_id`, `playlist_id`, `playlist_title`, `position`, `video_id`, `title`, `artist`, `thumb`, `duration_ms` | yes |
| `queue_items` | `id`, `party_id`, `video_id`, `title`, `artist`, `thumb`, `duration_ms`, `requested_by` (guest id; null = host), `requested_by_name`, `arrival_index`, `net_votes`, `host_rank`, `status` (queued/playing/played/removed), `created_at` | yes |
| `votes` | (`item_id`, `guest_id`) primary key, `value` (+1/-1) | no (`guest_state` returns the guest's own votes) |
| `private.videos` | data of videos seen in searches (`video_id`, title, artist, cover, length) | no |
| `private.search_cache` | normalized search → results, for 24 hours | no |
| `private.app_secrets` | the hash of the app key | no |

Shape of `now_playing`: `{ item_id, video_id, title, artist, thumb, duration_ms, position_ms, is_playing, started_at }`.
`started_at` is the server time at which `position_ms` was valid; the guest page computes the progress bar from it:
`position = position_ms + (now - started_at)` if `is_playing`. This way pause and seeking can be described too.

`settings` (can be overridden when creating the party, defaults):
`search_per_guest` 5, `search_per_party` 40, `max_queued_per_guest` 3, `request_cooldown_s` 30, `max_guests` 50.

Database-level safeguards (they hold even if a function had a bug):
- unique index: `(party_id, video_id)` where `status in ('queued','playing')` → no duplicates in the queue;
- unique index: `code` where `status = 'active'` → two active parties cannot share a code;
- the primary key of `votes` is `(item_id, guest_id)` → one vote per guest per song;
- unique index: `(party_id, lower(nickname))` → no two identical nicknames within a party.

## 3. Permissions: who can reach what

"Automatically expose new tables" is turned off in the project, so the migrations grant everything explicitly:

- **Tables**: the `anon` role gets only `SELECT`, and only on what the guest page needs
  (the allowed columns of `parties`, `queue_items`, `party_tracks`, `queue_view`). Nobody has `INSERT/UPDATE/DELETE`
  rights from the API. The `authenticated` role gets nothing, because there is no sign-in.
- **RLS**: enabled on every table. The three readable tables have a `SELECT` policy for the `anon` role;
  `party_guests` and `votes` have no policy, so they are invisible from outside.
- **Writing**: only through `SECURITY DEFINER` functions (the function runs with its owner's rights, so it can
  write; the caller can only do what the function allows). Every function runs with `search_path = ''`
  (it cannot be hijacked with an object smuggled in from another schema), and `EXECUTE` is granted explicitly:
  `anon` for the guest and host functions, only `service_role` for the internal search functions.
- **Concurrency**: every writing function first locks the party row (`SELECT ... FOR UPDATE`), so within a
  party the writes run one after the other: limits and `arrival_index` are computed without race conditions.

Why is the read policy `using (true)`? Guests have no sign-in, and Realtime can only check RLS by role, so we
cannot narrow it to "their own" party at row level. What anyone can read this way: song titles and nicknames.
What they cannot: the join code, the tokens, the hashes, and who voted for what.
Writing is impossible without knowing the code, because a guest token can only be obtained with `guest_join(code)`.

## 4. Functions (RPC)

Every function returns JSON: `{ "ok": true, ... }` or `{ "ok": false, "error": "<code>", ... }`.
The server returns an error code; the client writes the Hungarian text (web page: `app.js`, Deck: `strings.xml`).

| Function | Called by | What it does |
|---|---|---|
| `create_party(create_key, settings)` | Deck | cleans up, generates the code, host_secret and link token; returns `party_id`, `code`, `host_secret` (only now, once), `link_token` |
| `guest_peek(code, link?)` | guest | before the name: is the party in the link still alive (code + link token; without a token, for a code typed by hand, the code is enough) |
| `guest_join(code, nickname, link?)` | guest | checks the nickname, creates the guest; a wrong link token gives `party_not_found`; returns `party_id`, `guest_id`, `guest_token` |
| `guest_state(party_id, token)` | guest | own votes, searches left, cooldown, server time |
| `guest_request(party_id, token, video_id)` | guest | requests a song (rules: section 5) |
| `guest_vote(party_id, token, item_id, value)` | guest | +1 / -1 / 0 (withdraw); not on your own request |
| `guest_cancel(party_id, token, item_id)` | guest | withdraws your own request that is still waiting |
| `host_state(party_id, secret)` | Deck | party, guests and the ordered queue in one |
| `host_set_tracks(party_id, secret, tracks, replace)` | Deck | uploads playlist songs (in chunks, at most 5000) |
| `host_add(party_id, secret, track)` | Deck | the host puts a song into the queue |
| `host_move(party_id, secret, item_id, position)` | Deck | manual reordering (pinning, section 6) |
| `host_unpin(party_id, secret, item_id)` | Deck | releases a pin |
| `host_remove(party_id, secret, item_id)` | Deck | removes from the queue |
| `host_advance(party_id, secret, item_id?)` | Deck | next song (or a given item) → `playing`, sets `now_playing` |
| `host_set_now_playing(party_id, secret, now)` | Deck | reports the song playing now / pause / seek |
| `host_end(party_id, secret)` | Deck | ends the party, deletes the data |
| `search_begin / search_finish / search_fail` | only the Edge Function (`service_role`) | reserves the search limit, cache, refund |
| `cover_live_parties()` | only the `cover` Edge Function (`service_role`) | ids of the active parties (for the cleanup) |
| `test_age_party / test_run_cleanup` | only the tests (with the test key) | "ages" a TEST party and runs the cleanup |

A guest can only request a `video_id` the server knows: from the party's `party_tracks` rows or from the
results of an earlier search (`private.videos`). The server copies title, artist and cover into the queue;
the guest cannot send their own text, so the queue cannot be filled with fake titles or junk.

## 5. Rules against spam

| Rule | Where it is enforced | Error code |
|---|---|---|
| only an active party (younger than 12 hours) can be written to | at the start of every function | `party_not_found` |
| nickname 1–20 characters, trimmed, no control characters, basic profanity filter, unique in the party | `guest_join` | `bad_nickname`, `nickname_taken` |
| at most 50 guests per party | `guest_join` | `party_full` |
| the same `video_id` cannot be in the queue twice (queued/playing) | `guest_request` + unique index | `duplicate` |
| at most 3 waiting songs per guest | `guest_request` | `too_many` |
| 30 s between two requests per guest | `guest_request` | `cooldown` (+ `wait_s`) |
| one vote per guest per song (can be changed and withdrawn) | `guest_vote` + primary key | – |
| no voting on your own request | `guest_vote` | `own_item` |
| 5 searches per guest, 40 per party | `search_begin` | `search_limit_guest`, `search_limit_party` |
| host actions only with the `host_secret` | every `host_*` function | `forbidden` |
| creating a party only with the app key | `create_party` | `forbidden` |
| for the whole project: at most 2 parties at a time, 10 parties/day, 50 guest searches/day (Phase 8: the key in the APK is public) | `create_party`, `search_begin` | `too_many_parties`, `party_limit_day`, `search_limit_day` |

An honest limit: without sign-in a "guest" is a browser token. Someone who clears their browser data can join
as a new guest and gets 3 requests again. Per-party caps (50 guests, 40 searches) catch this, and the host
can delete anything. That is enough for a house party; it would not be for the public internet.

## 6. Order

**The server** computes the order in the `queue_view` view (`position` column); clients only display it.
This way the web page, Deck and the tests are guaranteed to see the same thing.

**Rule 1 – arrival.** Every request gets an `arrival_index` (1, 2, 3, … per party). Without votes this is the order.

**Rule 2 – votes: one vote = exactly one place.** `net_votes` = likes − dislikes (this is the score shown).
The order, however, does not come from a formula but from a **stored position** (`free_rank`): every vote
moves the song by as many places as the guest's vote changed, swapping it with its neighbour:

| The guest's vote | Movement |
|---|---|
| none → like | 1 place up |
| none → dislike | 1 place down |
| like → dislike | 2 places down |
| dislike → like | 2 places up |
| like / dislike withdrawn | 1 place back |
| the same again | nothing |

The first free song does not go higher, the last does not go lower (the vote still counts in the score). A new
request always goes to the end of the free queue. There are no ties: `free_rank` is different for every waiting song.

*Why not a formula (Phase 10 fix)?* It used to be `key = arrival_index − net_votes × 1.5`. A like only moved
a song past the one above it if their arrival numbers differed by less than 1.5, which is often not true:
played, deleted and withdrawn songs leave holes, and so does a song that was voted past another. Example:
A1 B2 C3 D4, like on C → A C B D; like on D: D's key is 4 − 1.5 = 2.5, B's is 2 → D **did not move**, although
it was right below B. In the same situation a dense number (row_number in arrival order) does not help either,
because D is 4th there too and B 2nd. With the stored position every vote steps over exactly the neighbour.

**Rule 3 – the host always wins.** If the host moves a song by hand, it gets a `host_rank`: the **number of its
slot** in the queue (1 = next). The pinned song stays in that slot; votes do not move it. The other (not pinned)
songs fill the free slots in their stored order. On a vote the free songs swap places among themselves; they
"jump over" the pinned ones.

To keep the pinned slots meaningful when the queue changes:
- if a song further ahead leaves the queue (starts, is deleted or withdrawn), the `host_rank` of the pinned
  songs behind it decreases by one (they move forward with the queue);
- if the host drags a song over pinned ones, the ones in between shift by one, just as the drag shows on screen;
- a new request always arrives at the end and does not affect the pinned ones.

### Example (the server's "order" test runs this too)

| Step | What happens | Free queue (without pinned songs) | Queue |
|---|---|---|---|
| 1 | A, B, C, D, E arrive in this order | A B C D E | A B C D E |
| 2 | E gets a like | E one place up | A B C **E** D |
| 3 | E gets another like | one more place | A B **E** C D |
| 4 | B gets a dislike | B one place down | A E **B** C D |
| 5 | The host drags D to slot 1 | D pinned: `host_rank` = 1; free: A E B C | **D** A E B C |
| 6 | D gets three dislikes | D is pinned, does not move | D A E B C |
| 7 | C gets three likes (from three guests) | C three places up: C A E B | D **C** A E B |
| 8 | The host steps the queue (`host_advance`) | D plays; it left the queue | C A E B |

Step 7 is the point: C reached the front of the free queue, yet it does not overtake D, because slot 1 is taken.

The "holes" case and quick votes in a row have their own test: "order: one like = exactly one place, also with
gaps and after other moves" (2 played/deleted songs, likes on different songs, like → dislike, withdrawal,
next to a pinned song, three guests voting at once).

### How the view computes it

1. Pinned songs go into their `host_rank` slots.
2. Free songs get a number by `free_rank`: k = 1, 2, 3, …
3. The slot of the k-th free song: `k + (the number of pinned slots before it)`. A song pinned to slot r
   (the j-th among the pinned ones) has `r − j` free slots before it, so it comes before the k-th free song
   if `r − j < k`.
4. At the end the slots are renumbered (1, 2, 3, …), so the numbering stays continuous even if a pinned
   slot points past the end of the queue.

## 7. Search for guests (Edge Function: `search`)

`POST /functions/v1/search` `{ party_id, token, q }` → `{ ok, results, searches_left, party_searches_left, cached }`.

1. `search_begin`: checks the guest; normalizes the search (lowercase, trim, multiple spaces → one).
   If there is a cache entry younger than 24 hours → returns it right away, **it does not count towards the limit**.
   Otherwise it checks and reserves the limit (guest +1, party +1).
2. YouTube Data API with the server's own key (Edge Function secret): `search.list` (`type=video`,
   `videoCategoryId=10`, 15 results) + `videos.list` for the lengths.
3. The same cleaning as in Deck (Phase 4): `TitleCleaner` logic (noisy brackets, splitting "Artist - Title",
   cutting "- Topic") and a duplicate filter (Topic channel > official channel > anything else; the winner
   stays at the place of its first occurrence). The Edge Function has a TypeScript copy of the Kotlin code.
4. `search_finish`: writes the results into the cache and the `private.videos` table (this is how
   `guest_request` knows the requested video exists).
5. On an error `search_fail` gives the reserved search back. Quota error → `error: "quota"`; the web page says so
   in Hungarian, and the Playlists tab keeps working (it does not use the YouTube API).

Quota: a new search costs 101 units (100 + 1). A party can use at most 40 × 101 = 4040 units, the daily budget
is 10,000. The server key is a separate Google Cloud key (not the app's), so a party does not use up the budget
of Deck's own search if it is in a separate project.

## 8. Realtime

- In the `supabase_realtime` publication: `parties` and `queue_items`. The guest page keeps two
  `postgres_changes` subscriptions: `parties` (`id=eq.<party>`: now playing, party over, searches left) and
  `queue_items` (`party_id=eq.<party>`).
- Realtime checks with the same `anon` role and RLS as REST, which is why the `SELECT` right and policy are needed.
- The client does not assemble the order from the events: on any `queue_items` event (merged with a short delay)
  it fetches `queue_view` again. Simpler, and the order is computed in a single place (the server).
- Status changes instead of deletion (`removed`, `played`): Realtime cannot filter DELETE events or check RLS on
  them; on UPDATE it can.
- Safety net: a full refresh every 20 s and when the page comes to the foreground (iPhone drops the WebSocket in the background).

## 9. Guest web page (`party-web/`, Vercel: `deck-party`)

- `index.html` (markup + CSS) and `app.js`, no framework, no build; `supabase-js` from a CDN.
- Address: https://deck-party.vercel.app. The QR link: `/?c=KQZT&p=k3m9x2qa` (the code + the party's own link
  token, Phase 10). First `guest_peek`: if the party in the link no longer exists (a new party may have got the
  code meanwhile), the "The party is over" page comes (with an "Another party's code" button); so an old QR code
  never leads into a later party. If it is alive: if there is a saved token for this party, straight in;
  otherwise the nickname (the previous one pre-filled). Without a code, a code input; for a code typed by hand
  the 4 letters are enough (it only finds an active party).
- Cover: if "now playing" has no image, a Deck cassette graphic (CSS, `--cassette`); an image arriving later
  fades in. The queue's thumbnails work the same way.
- `localStorage`: `deck.nickname` (last nickname), `deck.party.<CODE>` (`party_id`, `guest_id`, `guest_token`).
- A "now playing" card at the top; tabs: **Queue** (position, requester's name, 👍/👎, own request highlighted,
  can be withdrawn, without vote buttons) · **Playlists** (the host's lists, filter by list and text) ·
  **Search** (searches left shown).
- Cassette style: housing #D8D9D3, key #ECEDE7, trough #C4C5BE, ink #15161A, cobalt #2340D8, Archivo.
- Every text coming from users goes onto the page with `textContent` (no HTML injection).

## 10. Lifecycle and cleanup

- `host_end`: `status = ended`, all data of the party (guests, queue, votes, playlist songs) is deleted at once.
  The party row itself (worthless without the code and the secret) stays for 1 more hour, so guest pages left
  open get the "The party is over" message.
- `private.cleanup()` at the start of every `create_party` AND every hour (pg_cron `deck-party-cleanup`, at
  minute 7 of every hour): deletes ended parties (older than 1 hour) and parties created more than 12 hours ago
  with everything they have, the search cache older than 24 hours and video data older than 48 hours. Then the
  same job (pg_net) calls the `cleanup` action of the `cover` Edge Function, which deletes the cover folders of
  parties that are no longer active (Storage files cannot be deleted properly from SQL). `cleanup` can be called
  without a secret: it can only delete folders of inactive parties.
- Covers (Phase 10): if the playing song has no image link, Deck uploads the cover as a 300px JPEG through the
  `cover` Edge Function (`upload`, checked with the host secret) into the public `party-covers` bucket, a
  separate folder per party (`<party_id>/<key>.jpg`). Only the function can write (no storage policy). At the
  end of the party Deck calls `purge`, otherwise the hourly cleanup takes it. The image's CDN cache is 60 s:
  after deletion it is reachable for at most that long.
- The 12-hour limit holds independently of the cleanup: no function writes to a party older than 12 hours.

## 11. Deck's side in short

- The host starts a party from the player (choosing playlists, `create_party`, `host_set_tracks` in chunks, QR code).
- A pure Kotlin engine decides the stepping (no Android, network or clock in it), a foreground service carries
  it out: about 1.5 s before the end of the song `playFromUri(watch?v=<head of the queue>)`, then
  `host_advance`. Playback first, network second: the switch cannot wait for the server.
- If YT Music starts another song by itself (autoplay) and the queue is not empty, the engine overrides it with
  the head of the queue at once. With an empty queue YT Music goes on, and Deck only reports what plays.
- An uncertain match never starts a new song (YT Music gives no video id, only a title).
- Polling instead of Realtime: 3 s while the host view is visible, 10 s in the background.

## 12. Known limits

- The 4-letter code (24⁴ ≈ 330 thousand) is not a strong secret: someone who tries them all can join as a
  guest. The damage is limited (limits, the host can delete); it is not enough for host actions.
- Guest identity = browser token (see section 5).
- The progress bar relies on the host's reports: if Deck does not report a pause, the bar keeps running on the guest page.
- The search searches YouTube, not the YT Music catalogue (the same limit as in Phase 4).
- `party_tracks` holds at most 5000 songs per party.
