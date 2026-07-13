# Poet Music

Poet Music is an offline-first, pastel-themed music player for Android. The UI is an
[htmx](https://htmx.org) single-page app served by an embedded [Ktor](https://ktor.io)
server running inside the app process, rendered in a native WebView. Playback is
handled natively by [Media3 / ExoPlayer](https://developer.android.com/media/media3)
through a foreground `MediaSessionService`, so music keeps playing with the screen off
and shows lockscreen / notification controls.

## Features

- **Local library** — pick any folder with the system file picker (SAF); Poet scans it
  for audio files (`mp3`, `flac`, `m4a`, `aac`, `ogg`, `opus`, `wav`) and reads tags,
  album art and matching `.lrc` lyric files.
- **Library browsing** — Songs / Albums / Artists / Playlists tabs, live search, and a
  sort panel: Title A-Z, Title Z-A, Artist Descending, Artist Ascending, Date Added.
  The selected sort persists across screens and app restarts.
- **Now Playing** — seek bar, playback speed cycling, sleep timer, synced lyrics,
  favourite button, and a slide-up **queue** drawer showing the live play order
  (including the active shuffle order).
- **Shuffle & repeat** — dynamic icons for *Shuffle All* vs *Play in Order*, and four
  repeat states: play-through, *Repeat Playlist*, *Repeat One Song*, and
  *Play Single Song and Stop*.
- **Playback persistence** — the queue, position, shuffle/repeat/speed modes survive
  app restarts (restored paused).
- **Playlists & favourites** — long-press any song for quick actions: play next, add
  to queue, add to playlist, favourite, edit tags (ID3v2 written in place for MP3s),
  remove from library.
- **Theming** — pastel accent colors and canvas tints; the Android status bar follows
  the selected accent color.

## Architecture

```
┌────────────────────────────── Android app ──────────────────────────────┐
│                                                                          │
│  MainActivity ──── WebView ── http://127.0.0.1:8080 ──► PoetServer (Ktor)│
│      │                                                     │             │
│      │ SAF folder picker, status bar accent                │ HTML (htmx) │
│      │                                                     ▼             │
│      │                                    Views / Shell (server-rendered)│
│      │                                                     │             │
│  PlaybackService (Media3) ◄── PlayerController ◄───────────┘             │
│      │                                                                   │
│  MusicDatabase (SQLite): tracks, folders, playlists, settings            │
└──────────────────────────────────────────────────────────────────────────┘
```

- `server/` — Ktor routes (`PoetServer`), page shell + client JS (`Shell`), and
  server-rendered views (`Views`).
- `playback/` — `PlaybackService` (foreground Media3 session) and `PlayerController`
  (thread-safe bridge between server threads and ExoPlayer).
- `data/` — SQLite database, library scanner, LRC parser, MP3 tag editor.

The embedded server binds to `127.0.0.1` only and is never reachable from other
devices on the network.

## Building

Requirements: JDK 17+, Android SDK (compileSdk 36).

```bash
./gradlew assembleDebug     # debug APK  → app/build/outputs/apk/debug/
./gradlew assembleRelease   # release APK (unsigned)
```

Minimum Android version: 8.0 (API 26). Target: Android 14 (API 34).

## License

Licensed under the [Apache License 2.0](LICENSE).
