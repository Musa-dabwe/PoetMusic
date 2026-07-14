# Poet Music — Bug Log

A running record of every bug encountered across pull requests, with root
causes and the fixes that landed. **Check this file before refactoring the
shell JS, the settings screen, or playback plumbing** — several of these bugs
were fixed once, then reintroduced by later UI rewrites. If a change touches
an area listed here, re-verify the "How to verify" steps for that entry.

Legend: 🟣 fixed · 🔁 was fixed, got reintroduced, fixed again

---

## PR #1 — Initial implementation (`claude/fable-5-features-kis2g8`)

### 1.1 🟣 `.gitignore` corruption un-ignored `build/` and `local.properties`
- **Symptom:** build outputs and machine-local SDK paths showed up in `git status`.
- **Root cause:** a newline-less append merged the last two lines into
  `buildlocal.properties`, so neither path was ignored.
- **Fix:** rewrote the two entries on separate lines (commit `f3ef832`).
- **Lesson:** always `tail -c1` (or open) a dotfile after appending to it.

## PR #2 — UI improvements & bug fixes (`claude/poet-music-ui-fixes-906xgn`)

### 2.1 🟣 Context-menu click passthrough
- **Symptom:** tapping outside an open song context menu also activated the
  row/button underneath it.
- **Root cause:** the menu floated over the list with nothing intercepting
  the tap; the same tap that closed the menu reached the layout below.
- **Fix:** a full-screen transparent `.menu-shield` behind every open menu
  swallows the tap, and the owning row is lifted with `.row.menu-open`.

### 2.2 🟣 Context menus clipped at the bottom of the screen
- **Symptom:** menus for the last rows opened partially behind the media tray.
- **Fix:** `positionMenu()` measures the opened menu and flips it upward when
  it would cross `window.innerHeight - 78` (the tray line).

### 2.3 🟣 Library tab/sort state lost between screens and restarts
- **Symptom:** navigating away from the library and back reset the active tab
  and sort order.
- **Fix:** tab and sort are persisted in the settings table (`lib_tab`,
  `lib_sort`) and used as fallback when the query params are absent.

### 2.4 🟣 Playback state lost on app restart
- **Fix:** `PlayerController` persists queue/index/position/shuffle/repeat/
  speed every ~3 s and `restoreState()` rebuilds the paused queue on service
  start.

## PR #3 — Component redesign & regression fixes (this PR)

### 3.1 🔁 Onboarding tip killed without user interaction on first launch
- **Symptom:** on a fresh install the Settings screen with the "Start here"
  onboarding banner appeared for a moment, then was replaced by the empty
  Library screen with no tap from the user.
- **Root cause (regression mechanism):** two racing initial loads. The main
  container carried `hx-get="/screens/library" hx-trigger="load"` **and**
  `DOMContentLoaded` scheduled `poetGo('/screens/settings?tip=1')` 250 ms
  later. Whichever response landed last won the swap; when the library
  response arrived late it swapped the onboarding screen (and its tip) away.
- **Fix:** the main container no longer self-loads. `DOMContentLoaded` issues
  exactly one request — `/screens/settings?tip=1` on first run, otherwise
  `/screens/library`. There is nothing left to race.
- **Do not reintroduce by:** adding `hx-trigger="load"` back to
  `#main-container`, or adding any delayed automatic `poetGo(...)` at startup.
- **How to verify:** clear app data, launch: the tip must stay up until "Got
  it" / add-folder is tapped.

### 3.2 🔁 Now Playing screen flickers every second
- **Symptom:** the whole Now Playing screen faded/re-rendered once per second
  while a track was loaded (visible as a rhythmic flicker; sliders and lyric
  scroll positions jumped).
- **Root cause (regression mechanism):** the `htmx:afterSwap` handler reset
  `poetShownTrack = -1` after **every** main-container swap "to force a
  refresh". The 1 s state poller then saw `trackId !== poetShownTrack`,
  re-fetched `/screens/now-playing`, which swapped the container, which reset
  the marker to -1 again — an infinite refetch loop, each iteration replaying
  the `.screen` fade-in animation.
- **Fix:** after a swap, `poetShownTrack` is read from the rendered
  `#np-root[data-track-id]` instead of being forced to -1. The poller now
  only re-fetches when the playing track genuinely changed.
- **Do not reintroduce by:** "forcing" refreshes via sentinel values that the
  poller reacts to; always sync markers with the DOM that was just rendered.
- **How to verify:** open Now Playing, let a song play 30 s: no fade pulses;
  only the slider/time advance. Track auto-advance still re-renders once.

### 3.3 🔁 Disappearing / blinking UI elements in the Settings panel
- **Symptom:** elements of the Settings screen (scan button, occasionally
  neighbouring cards) blinked or briefly vanished while the screen was open;
  a button being pressed could disappear under the user's finger.
- **Root causes (two compounding):**
  1. The scan card polled `/partial/scan` with `hx-trigger="every 2s"`
     **unconditionally**, replacing the card's DOM every 2 s forever — even
     when no scan was running. Android WebView repaints the swap visibly, and
     any in-progress touch/focus on the replaced nodes was dropped.
  2. `#tip-shield` used `backdrop-filter: blur(1px)` on a full-screen fixed
     overlay; blur on large fixed surfaces is a known Android WebView
     compositing glitch that can blank the layers underneath.
- **Fix:** the polling wrapper is now rendered *inside* the swapped content
  and only while `LibraryScanner.isScanning` is true, so polling starts when
  a scan starts and stops with the final swap; the tip shield keeps its dim
  but drops `backdrop-filter`.
- **Do not reintroduce by:** putting `hx-trigger="every Ns"` on an element
  that outlives the condition it polls for, or adding `backdrop-filter` to
  full-screen fixed overlays (small floating panels like menus are fine).
- **How to verify:** sit on Settings for a minute with no scan running — the
  network log shows no `/partial/scan` traffic and nothing blinks.

### 3.4 🟣 Audio playback cut off shortly after minimizing the app
- **Symptom:** music stopped a few seconds/minutes after the app was
  minimized or the screen turned off.
- **Root cause:** ExoPlayer held no wake lock (`setWakeMode` was never
  configured), so Doze parked the CPU once the activity went to the
  background; the foreground media notification alone does not keep the CPU
  awake. (`WAKE_LOCK` permission was already declared but unused.)
- **Fix:** `ExoPlayer.Builder(...).setWakeMode(C.WAKE_MODE_LOCAL)` in
  `PlaybackService` — the player now holds a partial wake lock only while
  playing local files. The Media3 `MediaSessionService` notification remains
  the persistent-but-dismissible controls surface (ongoing while playing,
  swipeable when paused).
- **How to verify:** start a song, minimize the app and turn the screen off
  for several minutes — playback must continue through track changes.

### 3.5 🟣 Web-browser-looking "http://127.0.0.1:8080 says" confirm dialogs
- **Symptom:** removing a folder/track or deleting a playlist raised the
  WebView's default JS confirm dialog, exposing the localhost origin and
  breaking the native illusion.
- **Root cause:** `hx-confirm` calls `window.confirm()`, which the default
  `WebChromeClient` renders as the stock "URL says" alert.
- **Fix:** all `hx-confirm` uses were removed and replaced with the pastel
  confirmation modal (`Views.confirmModal`): rounded 18 px card over a
  blurred dim, borderless CANCEL that swaps an empty fragment into
  `#modal-root`, filled accent OK firing the destructive request
  (`hx-delete /api/settings/folder` for folders).
- **Do not reintroduce by:** using `hx-confirm`, `confirm()`, `alert()` or
  `prompt()` anywhere in served markup. Grep for them before merging.

### 3.6 🟣 Permissions asked lazily / storage never asked
- **Symptom:** notification permission was requested at launch, but broad
  storage access never was; on some devices the media notification and folder
  scans misbehaved until permissions were granted manually.
- **Fix:** one combined runtime request at initial launch:
  `READ_MEDIA_AUDIO` + `POST_NOTIFICATIONS` on Android 13+, legacy
  `READ_EXTERNAL_STORAGE` below (manifest-declared with `maxSdkVersion=32`).
  SAF folder grants remain the source of truth for library scanning.

---

## Design-file delta: `Poet_Music.initial.html` → `Poet_Music.dc_new.html`

What changed between the two design prototypes (and is now implemented in the
real app):

| Area | initial.html | dc_new.html |
|---|---|---|
| Sorting | none (no sort UI at all) | "Sort" button on the Songs tab opens a **bottom drawer** with custom radio indicators and exactly five states: Title A–Z, Title Z–A, Artist A–Z, Artist Z–A, Date modified |
| Track data | title/artist/duration only | tracks carry `album`, `genre`, `year`, `trackNo`, `modified` — needed by the tag editor and Date-modified sort |
| Folder removal | immediate delete on ✕ | ✕ opens a **rounded 18 px confirmation card** ("Are you sure you want to remove *folder* from the library?") with borderless CANCEL / filled OK |
| Context menu | Play next, Add to playlist | adds **Edit tags** entry |
| Tag editing | none | **slide-in bottom sheet** with six focus-ring fields (title, artist, album, genre, year, track number) and a persistent "Save Metadata Changes" header button |
| Notification widget | none | **compact widget preview** in Settings: 10 px-rounded cover thumb, bold 14 px title / muted 12 px artist, live mm:ss timestamp, prev/play/next cluster, outline↔filled favorite heart (no progress bar) |
| Animations | spin, bob | adds `poet-sheet-up`, `poet-fade-in`, `poet-pop` (heart/radio pop) |
| Queue stepping | index arithmetic on a fixed array | steps through the *sorted* list, so next/prev follow the visible order |

## Regression checklist before merging UI changes

- [ ] Fresh install: onboarding tip appears and stays until dismissed (3.1)
- [ ] Now Playing shows no 1 Hz flicker (3.2)
- [ ] Settings idle: no `/partial/scan` polling, no blinking (3.3)
- [ ] Minimize + screen off: playback continues (3.4)
- [ ] No `hx-confirm` / `confirm(` / `alert(` in `rg` output over `server/` (3.5)
- [ ] First launch shows the combined permission dialog (3.6)
