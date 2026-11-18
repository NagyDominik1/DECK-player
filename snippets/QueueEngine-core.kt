// EXCERPT from Deck's queue engine (queue/QueueEngine.kt, ~530 lines in total).
// Not compilable on its own: types such as PlayerSnapshot, QueueItem, EngineState and the
// helpers matches(), isAd(), report() are left out. It shows the decision core only.
//
// The engine is pure Kotlin: no Android, no network, no clock. The caller (a foreground service)
// feeds it a snapshot of YouTube Music's media session plus the head of Deck's queue a few times
// per second, and carries out the actions it returns. That is why every rule has a unit test.
//
// The hard part: YouTube Music exposes no video id and fills its metadata in steps
// (title -> artist -> title with "(feat. X)"), so "is this the song I started?" is a guess.
// The rule of thumb throughout: an uncertain match never starts a new song.

object QueueEngine {
    const val SWITCH_BEFORE_END_MS = 1500L // rule 1: start the next song this long before the end
    const val START_GRACE_MS = 8000L       // changes right after our start count as our start
    const val AD_MAX_WAIT_MS = 180_000L    // an "ad" longer than this is not an ad

    fun decide(state: EngineState, input: EngineInput, rules: QueueRules): Decision {
        val now = input.nowMs
        val player = input.player
        // Between two songs the metadata can be empty for a moment: wait for real data.
        if (player == null || player.title.isBlank()) return Decision(state)

        // Rule 7 (YT Music without Premium): an ad is left alone. Not overridden, not counted,
        // not reported. Several independent signals, any is enough (session flag, title "Ad",
        // no artist + short, not seekable). Capped, so a wrong guess cannot stall a party.
        val adSince = state.adSinceMs ?: now
        if (isAd(player) && now - adSince < AD_MAX_WAIT_MS) return Decision(state.copy(adSinceMs = adSince))

        when (val cur = state.current) {
            // First look (party just started): whatever plays may finish, the queue follows.
            null -> return onCurrent(state.copy(current = Current.External(player.key)), player, input, rules)

            is Current.External ->
                if (!sameSong(player.key, cur.key)) return foreign(state, player, input, rules)

            is Current.Started -> if (cur.key == null) {
                // We asked YT Music for this song (playFromUri) and wait for it to show up.
                return when {
                    matches(player, cur.item.track) -> onCurrent(state.recognized(cur, player), player, input, rules)
                    now - cur.startedAtMs < START_GRACE_MS -> Decision(state) // YT Music may flash its own next song first
                    // Nothing moved at all: the request was lost, ask once more for the SAME song.
                    sameSong(player.key, cur.keyBefore) && cur.attempts < 2 -> Decision(state.retry(cur, now),
                        listOf(EngineAction.Play(cur.item, advance = false)))
                    // Something plays that we cannot match by title (YouTube and YT Music name songs
                    // differently). Accept it: guessing "wrong song" would skip through the queue.
                    else -> onCurrent(state.recognized(cur, player), player, input, rules)
                }
            } else if (!sameSong(player.key, cur.key)) return foreign(state, player, input, rules)
        }
        return onCurrent(state, player, input, rules)
    }

    /** Rule 2: a song plays that Deck did not start (YT Music autoplay, a skip, the user's pick). */
    private fun foreign(state: EngineState, player: PlayerSnapshot, input: EngineInput, rules: QueueRules): Decision {
        val head = input.head
        // Party: the queue always wins. Solo ("play next"): only a skip to YT Music's next row
        // (recognized afterwards: queue row id + 1) or a missed natural end gives way to the queue.
        // A song the user picked on purpose is never overridden.
        if (head != null && (rules.overrideForeign ||
                (rules.followSkips && (endedNaturally(state.lastSeen) || isQueueStep(state.lastSeen, player))))) {
            return start(state, head, input.nowMs)
        }
        return onCurrent(state.copy(current = Current.External(player.key)), player, input, rules)
    }

    /** Rule 1: switch ~1.5 s before the end, but only once the position was seen moving far from the end. */
    private fun onCurrent(state: EngineState, player: PlayerSnapshot, input: EngineInput, rules: QueueRules): Decision {
        val armed = state.current!!.armed ||
            (player.isPlaying && player.positionMs < player.durationMs - 3000) // stale position of the OLD song can't fire it
        val head = input.head
        if (head != null && armed && player.isPlaying &&
            player.durationMs - player.positionMs <= SWITCH_BEFORE_END_MS) return start(state, head, input.nowMs)
        return if (rules.report) report(state.armed(armed), player, input.nowMs) else Decision(state.armed(armed))
    }
}
