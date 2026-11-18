> Cleaned-up version from Deck's private repository. Magyar változat: [spike-playback-hu.md](spike-playback-hu.md).
> No secrets, internal references or project identifiers.

> Postscript: the decision was to search through the YouTube Data API and start the chosen result with `playFromUri`.

# Spike: starting a specific song / playlist in YT Music

**Question:** can Deck start a SPECIFIC song or playlist in YouTube Music?
Quick start (Phase 4) and party mode's automatic next song (Phase 6) depend on it.

**Short answer: yes.** `MediaController.transportControls.playFromUri()` with a
`https://music.youtube.com/watch?v=<videoId>` link starts exactly the requested song in the background,
without bringing YT Music to the front. Adding to the queue ("play next"), however, is not possible.

## Test setup

| | |
|---|---|
| Phone | Galaxy S25 (SM-S931B), Android 17, One UI 9.0 |
| YT Music | 9.45.51 (`com.google.android.apps.youtube.music`) |
| Date | 2026-11-15 |
| Method | A temporary developer button row in the app (removed since), pressed over adb; results come from logcat, the output of `dumpsys media_session` and screenshots. Every test starts a different song, so it is clear which method started what. |

What the YT Music session advertises about itself (`PlaybackState.actions = 2600887`, `flags = 3`):

| Capability | Advertised? |
|---|---|
| `ACTION_PLAY_FROM_SEARCH` | yes |
| `ACTION_PLAY_FROM_MEDIA_ID` | yes |
| `ACTION_PLAY_FROM_URI` | yes |
| `ACTION_SKIP_TO_QUEUE_ITEM` | no (but it works, see below) |
| `FLAG_HANDLES_QUEUE_COMMANDS` (editing the queue) | no |
| Reading the queue (`controller.queue`) | yes, a 25-item window titled "Következő" (Up next) |

Lesson: the advertised capabilities are not reliable. Two of the three are advertised and still do not work,
and one is not advertised and works anyway. Everything had to be tried.

## Results

"Background" = YT Music runs and has an active session (playing or paused), but Deck is in the foreground.
"Closed" = after `am force-stop`, no session.

| # | Method | Works? | Right song? | Brings YT Music to the front? | Note |
|---|---|---|---|---|---|
| 1 | `playFromSearch("Daft Punk One More Time", extras)` (FOCUS + ARTIST + TITLE) | **No** | – | no | With playback paused, the session went into `ERROR` state: "This song can't be played." While playing it was silently ignored. |
| 1b | `playFromSearch("Daft Punk Get Lucky", null)` | **No** | – | no | Nothing happened, no error either. |
| 2a | `playFromMediaId("dQw4w9WgXcQ")` (YouTube video id) | **No** | – | no | Nothing happened. YT Music probably expects its own internal mediaId format, which we do not know. |
| 2b | `playFromUri("https://music.youtube.com/watch?v=9bZkp7q19f0")` | **Yes** | yes (Gangnam Style) | **no** | Switches immediately, from paused and from playing. The queue is replaced by the song's "radio" (25 items). |
| 2d | `playFromUri(".../watch?v=FTQbiNvZqaY&list=RDAMVMFTQbiNvZqaY")` | **Yes** | yes (Toto – Africa) | no | Song + list together: the song starts, the list provides what follows. |
| 2g | `playFromUri(".../watch?v=<id>&list=PL…")` with a public playlist | **Yes** | the list started | no | The given video was not in the list, so the playlist's first song started. In other words the `list` parameter wins. |
| 2h | `playFromUri(".../watch?list=PL…")` (list only, no video) | **Yes** | the list's first song | no | **This is how to start a playlist in the background.** |
| 2c | `playFromUri(".../playlist?list=LM")` (Liked music) | **No** | – | no | Nothing happened. |
| 2f | `playFromUri(".../playlist?list=PL…")` public playlist | **No** | – | no | The `playlist?list=` URL form does not work, only `watch?list=`. |
| 2e | `playFromUri("https://www.youtube.com/watch?v=…")` | **No** | – | no | Only the `music.youtube.com` host works. |
| 3 | Intent `MEDIA_PLAY_FROM_SEARCH` to the YT Music package | **Partly** | – | **yes** | Opens the search results ("Queen Bohemian Rhapsody"), but **starts nothing**; the user has to tap. |
| 4 | Intent `ACTION_VIEW` `.../watch?v=kJQP7kiw5Fk` | **Yes** | yes (Despacito) | **yes** | Also works with YT Music closed: it launches the app and the song. |
| 5a | Intent `ACTION_VIEW` `.../playlist?list=LM` | **Partly** | – | yes | Opens the playlist page (Liked music) but does not start it. |
| 5b | Intent `ACTION_VIEW` `.../watch?v=djV11Xbc914&list=RDAMVM…` | **Yes** | yes (Take On Me) | yes | Song + list, starts. |
| 6a | `skipToQueueItem(queueId)` in YT Music's own queue | **Yes** | yes (Cola Song) | no | You can jump to items already in the queue, although the session does not advertise it. |
| 6b | Add to queue / "play next" | **Not possible** | – | – | The session does not handle queue commands (`FLAG_HANDLES_QUEUE_COMMANDS` is missing), and there is no intent for it. I did not try to force the command, because the official client (MediaControllerCompat) refuses it for such a session too. |

### Background / closed

| State | `playFromUri` (controller) | `ACTION_VIEW` (intent) |
|---|---|---|
| YT Music in the background, playing | works, stays in the background | works, comes to the front |
| YT Music in the background, paused | works, stays in the background | works, comes to the front |
| YT Music closed (force-stop) | **does not work: no session, nothing to control** | works: launches the app and the song, comes to the front |
| Right after a cold start | works (the session exists) | – |

More observations:
- After a force-stop the `MEDIA_PLAY` media key event does **not** wake YT Music up (no session is created).
  So a closed app cannot be started from the background; that needs `ACTION_VIEW`, which brings it to the front.
- **Not tested:** a YT Music swiped away from Recents (not force-stopped), and whether `playFromUri` also works
  when Deck itself is in the background or the screen is locked. Background restrictions should not apply to
  the controller call (they do apply to `startActivity`!), but Deck has to stay alive for it (foreground
  service); this had to be tried at the start of Phase 6.
- The video id is a plain YouTube video id (the same the YouTube Data API v3 search returns),
  so search results can be used directly as input.

## Recommendation

**Build party mode's "next song" on `playFromUri("https://music.youtube.com/watch?v=<videoId>")`.**
It is the only method that starts the exact song, in the background, without bringing YT Music to the front.

Since queueing is not possible, Deck has to run the queue itself (its own queue):

1. A guest requests a song → Deck puts it into its own queue (Supabase), with the video id.
2. Deck watches the position (it already can), and about 1-2 seconds before the end of the song it calls
   `playFromUri` for the next item. ("auto-switch near track end")
3. Safety net: if the switch was late and YT Music already started something from its own radio
   (the metadata changed, but not to our song), immediately `playFromUri` ours.
4. If the own queue is empty, do nothing: YT Music goes on with its own queue.

Risks and limits:
- **No crossfade:** the switch is a hard cut, and the last 1-2 seconds of the song are lost.
- **An active session is needed.** If YT Music is closed, it has to be brought to the front once when party mode
  starts (`ACTION_VIEW` for the first song, or `getLaunchIntentForPackage`); after that everything can run
  from the background.
- **Undocumented behaviour.** Support for `playFromUri` is YT Music's internal decision; an update can break it.
  It is worth checking a few seconds after the call that the requested song really plays, and if not,
  falling back to `ACTION_VIEW` (which brings YT Music to the front).
- Deck has to run in the background during the party → foreground service + an exemption from Samsung's
  battery optimization (onboarding asks for it).

For quick start (Phase 4):
- **Playlist buttons:** `playFromUri(".../watch?list=<playlistId>")` when there is a session; otherwise
  `ACTION_VIEW` to the same link. With your own playlists (e.g. `LM`, Liked music) the `watch?list=` form
  was tried later: it works.
- **Search field:** `MEDIA_PLAY_FROM_SEARCH` only gets as far as the results list, it starts nothing.
  The original Phase 4 plan relied on it, so it had to be rethought: either accept that the search ends in
  YT Music, or move the search forward to the YouTube Data API and start the result with `playFromUri`.
