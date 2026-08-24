# Poet Music

Poet Music is an offline-first, pastel-themed music player for **Android and Linux**.
It plays the music already on your disk: no account, no catalogue, no sign-in.

Both builds are one application. The UI is an [htmx](https://htmx.org) single-page app
served by an embedded [Ktor](https://ktor.io) server running inside the app process and
bound to loopback; the view layer and the routing table live in
`:core` and are shared byte-for-byte, while the frontend assets live in
`app/src/main/assets/web/`. Only the platform seams differ — playback,
storage, scanning, tag writing and the window.

| | Android | Linux |
| --- | --- | --- |
| Window | `WebView` in `MainActivity` | GTK 3 + WebKitGTK, bound through JNA |
| Playback | [Media3 / ExoPlayer](https://developer.android.com/media/media3) in a foreground `MediaSessionService` | GStreamer `playbin` |
| Storage | `SQLiteOpenHelper` | SQLite over JDBC |
| Files | Storage Access Framework | `java.nio` |
| Tags | hand-rolled ID3v2 writer (MP3) | JAudiotagger (MP3, FLAC, OGG, M4A) |
| System controls | lockscreen / notification, home-screen widget | MPRIS on the session bus |

## Features

- **Local library** — pick any folder (the system file picker on Android, a GTK chooser
  on Linux); Poet scans it for audio files (`mp3`, `flac`, `m4a`, `aac`, `ogg`, `opus`,
  `wav`) and reads tags, album art and matching `.lrc` lyric files.
- **Library browsing** — Songs / Albums / Artists / Genres / Playlists tabs, live search,
  and a per-tab sort drawer (Songs alone offers nine orders, from Title A-Z to Shortest
  first). Each tab remembers its own order across screens and restarts.
- **Now Playing** — seek bar, synced lyrics, playback speed cycling, a ten-band
  equaliser with presets, favourite button, and a slide-up **queue panel**: a pinned Now
  Playing card with animated EQ bars, plus a *Next up* list with per-song remove,
  drag-to-reorder handles, and a Clear action.
- **Musicolet-style queue** — playlists are fixed reference lists; the queue is a
  temporary working copy. Shuffling writes a new static randomized sequence
  (Fisher-Yates) into the queue, un-shuffling restores the original order around the
  playing song, and *Play next* inserts right after the current track.
- **Shuffle & repeat** — *Shuffle All* vs *Play in Order*, and three repeat states:
  *Repeat Playlist*, *Repeat One Song*, and *Play Single Song and Stop*.
- **Sleep timer** — ends on a clock (enforced against wall time, so a suspended machine
  cannot make it fire late) or after a set number of songs.
- **Playback persistence** — the queue (including the exact shuffle order), position and
  shuffle / repeat / speed modes survive a restart, restored paused.
- **Playlists & favourites** — long-press any song for quick actions: play next, add to
  queue, add to playlist, favourite, edit tags, remove from library.
- **Tag editor** — a full-height three-tab sheet (Details / Artwork / Lyrics). Details
  covers title, artist, album, album artist, genre, year, track/disc number, composer and
  comment, with an optional "rename file from tags" pattern. Artwork embeds a cover
  picked from disk or strips the existing one. Lyrics stores unsynced text and includes a
  synced-LRC maker that stamps `[mm:ss.xx]` timestamps against live playback and exports
  a sidecar `.lrc`.
- **Listening Journal** — tap the Poet mark in the header for a full-screen report over
  your own archive: track / album / hour totals, top artists / songs / albums / genres,
  listening habits (most active day, longest streak, peak hour, discovery split, decade
  focus) counted from songs you actually listened to, and library health (how much of the
  library you have explored, tag integrity, missing cover art, paired `.lrc` files, format
  mix). Every leaderboard shows ten entries; tapping a top artist or album opens it
  exactly as the Artists / Albums tabs do.
- **Theming** — pastel accent colours and canvas tints, light and dark. On Android the
  status bar follows the accent.
- **System integration** — Android gets lockscreen and notification controls and a home
  screen widget; Linux gets MPRIS, so the desktop panel applet, the lock screen and the
  keyboard media keys drive playback like any other player.

## Architecture

```text
                    ┌───────────────── :core ─────────────────┐
                    │  Views* / Shell   server-rendered HTML   │
                    │  PoetRoutes       the whole routing table│
                    │  PlayerPort  LibraryStore  HostPort      │
                    │  ScanPort  TagPort  EqPort   the seams   │
                    └────────────┬───────────────┬─────────────┘
                                 │               │
        ┌────────────────────────┴──┐         ┌──┴──────────────────────────┐
        │ :app  (Android)           │         │ :desktop  (Linux)           │
        │  MainActivity + WebView   │         │  WebKitWindow (GTK/JNA)     │
        │  PlaybackService (Media3) │         │  GstPlayer (GStreamer)      │
        │  MusicDatabase (SQLite)   │         │  DesktopLibrary (JDBC)      │
        │  LibraryScanner (SAF)     │         │  DesktopScanner (nio)       │
        │  TagEditor (ID3v2)        │         │  DesktopTags (JAudiotagger) │
        │  PoetWidgetProvider       │         │  MprisService (D-Bus)       │
        └───────────────────────────┘         └─────────────────────────────┘

                    app/src/main/assets/web/  — the frontend,
                    served byte-for-byte by both builds
```

The embedded server binds to `127.0.0.1` only and is never reachable from other devices
on the network. See [SECURITY.md](SECURITY.md).

Design and decision records live in `docs/`:
[`desktop-app-plan.md`](docs/desktop-app-plan.md) (the Linux build),
[`native-ui-solidification.md`](docs/native-ui-solidification.md) (the `:core` split),
and `docs/mockups/` (the layout tiers).

## Building

Requirements: JDK 17+. The Android build also needs the Android SDK (compileSdk 36).

```bash
./gradlew assembleDebug           # debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease         # release APK (unsigned)
./gradlew test                    # the shared test suite

./gradlew :desktop:run            # run the Linux app from the source tree
./gradlew :desktop:packageDeb     # installable .deb → desktop/build/deb/
```

Android: minimum 8.0 (API 26), target Android 14 (API 34).

Linux: the `.deb` carries its own Java runtime but binds the system's GTK 3, WebKitGTK
and GStreamer, so it stays small and picks up the distro's security updates. Those are
declared as package dependencies and are stock on a Debian or Ubuntu desktop. Set a real
maintainer address for a release build with
`-Ppoet.deb.maintainer=you@example.com`.

## License

Licensed under the [Apache License 2.0](LICENSE).

## Credits

- Placeholder cover art: [Designed by rawpixel.com / Freepik](https://www.freepik.com).
  The original image was modified (cropped/resized) for in-app use, as permitted
  by the Freepik free license.

---
