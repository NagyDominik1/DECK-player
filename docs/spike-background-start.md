> Cleaned-up version from Deck's private repository. Magyar változat: [spike-background-start-hu.md](spike-background-start-hu.md).
> No secrets, internal references or project identifiers.

# Spike: starting YouTube Music in the background, without opening it

**Goal:** when there is no YouTube Music media session, Deck should not open YouTube Music (a view intent brings
it to the front), but wake it up in the background and then start the requested song with the usual
`playFromUri`. The YouTube Music screen should appear as rarely as possible.

**Result:** it works in every "closed" state, also with the screen locked and Deck in the background. The
requested song plays 1.1–1.6 s after the start, and nothing comes to the front. The view intent is only a
fallback now.

| | |
|---|---|
| Phone | Galaxy S25 (SM-S931B), Android 17 / One UI |
| YT Music | 9.46.51 |
| Dates | 2026-11-18, re-checked 2026-11-19 |
| Method | A debug-only broadcast receiver (not in the release APK) runs each step on command over adb; results from logcat and `dumpsys media_session` |

## Methods

| Method | Works? | Time | Notes |
|---|---|---|---|
| Connecting to YouTube Music's media browser service | **Refused** | 8 ms (process alive), 130–190 ms (stopped) | YouTube Music only lets Google's own clients in. **The side effect is the key:** the bind starts the YouTube Music process even after a force-stop, and the process creates an *inactive* session. `getActiveSessions` does not return it yet, so it cannot be controlled yet. |
| Media PLAY key (`AudioManager.dispatchMediaKeyEvent`) | **Yes**, if YouTube Music is the media key receiver | the session appears 100–250 ms after the key | The inactive session wakes up with its last song; Deck pauses it at once, then calls `playFromUri`. The old song is never heard (states: NONE → STOPPED → PLAYING with the new song). After a force-stop the key **alone does nothing** (a stopped app gets no broadcasts): the bind has to come first. A key sent before the bind is lost. |
| Explicit `ACTION_MEDIA_BUTTON` broadcast to YouTube Music's receiver | **Not tried on purpose** | – | Since Android 12 an app in the background may not start a foreground service from a broadcast another app sent. YouTube Music's receiver would do exactly that: in the worst case YouTube Music crashes. Not worth the risk. |
| View intent with the link (the old way) | Yes | ~2–3 s | Brings YouTube Music to the front. Kept only as a fallback. |

## Measurements per state (bind → key → `playFromUri`)

| State | How it was produced | What is needed | Session | Requested song plays | Comes to front? |
|---|---|---|---|---|---|
| (a) paused, session alive | pause command | nothing (worked before too) | yes | at once | no |
| (b) process alive, session inactive | force-stop + bind, no key | key | +166 ms | +1146 ms | no |
| (c1) swiped away / killed by the system | `am kill` after a pause | key (bind not even needed) | +261 ms | +1245 ms | no |
| (c2) force-stopped | `am force-stop` | bind, then key | +609 ms | +1580 ms | no |
| (c2) with the screen locked | force-stop + sleep key | the same | +611 ms | +1638 ms | no |
| (c2) key without bind | force-stop, key only | – | **none** | – | no |
| (c2) key at +8 ms (before the bind answered) | force-stop | – | **none** (lost) | – | no |

Other observations:
- After a pause YouTube Music keeps its session active for at least 9 minutes, but stops its foreground service
  at once, so the system may kill the process at any time. In practice (c1) is the common case.
- Once started, YouTube Music keeps playing with its own foreground service.

## Timing of the key

The key needs a moment after the bind. With an identical phone and force-stopped YouTube Music, a PLAY key at
450 ms woke the session 0 times out of 3, at 500 ms 3 times out of 3. The first key therefore goes out at 500 ms
at the earliest, one repeat follows 1.2 s later (PLAY is not a toggle, a second one does no harm), and Deck gives
up after 3 s. In every working case the session arrived within 0.2–0.6 s, so 3 s is plenty.

These decisions live in a small pure Kotlin plan with its own unit tests (when the key may go, when to repeat,
when to give up); the Android side only executes it, and only one wake-up runs at a time.

## Safety: the key must reach YouTube Music and nothing else

Android gives the PLAY key to the app it currently treats as the media key receiver, which is not necessarily
YouTube Music. Before every PLAY key (also the repeat) Deck asks the system for that receiver
(`MediaSessionManager.getMediaKeyEventSessionPackageName()`, Android 13 and newer) and sends the key only if it is
YouTube Music. The first version decided from what Deck had last seen playing; on the phone it then refused to
send the key because plain YouTube had played last, although Android already routed media keys to YouTube Music.
Asking the system fixed that. On Android 12, where that call does not exist, Deck checks the last player it saw
and that no other app has an active session. If another app started anyway, Deck pauses it and falls back.

## In the app

Every place that starts music uses the same wake-up first: search hits, home screen cards, the library, MIX,
Deck's own queue (also with the screen locked) and the party. If it fails, Deck says so and lets you try again
from the same place; opening YouTube Music itself is a separate, explicit button in the settings.

| Case | Log | Result |
|---|---|---|
| Home card, YouTube Music force-stopped, Deck in front | bind refused +163 ms, key +469 ms, session +632 ms | the song plays, Deck stays in front |
| Own queue, YouTube Music killed, Deck in background, **screen locked** | bind +120 ms, key +455 ms, session +565 ms | the head of the queue plays, no "tap here" notification |

## Known limits

- **YouTube Music that never played** (fresh install, empty history): not tried, it would have needed wiping
  YouTube Music's data. Probably the key has nothing to resume, and after 3 s Deck reports that it did not start.
- A future YouTube Music update may change how the bind or the key behaves. Then the 3 s limit runs out and Deck
  falls back; it cannot get worse than before.
