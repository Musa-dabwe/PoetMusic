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
  favourite button, and a slide-up **queue panel**: a pinned Now Playing card with
  animated EQ bars, plus a *Next up* list with per-song remove, drag-to-reorder
  handles, and a Clear action.
- **Musicolet-style queue** — playlists are fixed reference lists; the queue is a
  temporary working copy. Shuffling writes a new static randomized sequence
  (Fisher-Yates) into the queue, un-shuffling restores the original order around
  the playing song, and *Play next* inserts right after the current track.
- **Shuffle & repeat** — dynamic icons for *Shuffle All* vs *Play in Order*, and four
  repeat states: play-through, *Repeat Playlist*, *Repeat One Song*, and
  *Play Single Song and Stop*.
- **Playback persistence** — the queue (including the exact shuffle order), position,
  shuffle/repeat/speed modes survive app restarts (restored paused).
- **Playlists & favourites** — long-press any song for quick actions: play next, add
  to queue, add to playlist, favourite, edit tags, remove from library.
- **Tag editor** — a full-height three-tab sheet (Details / Artwork / Lyrics) that
  writes ID3v2 frames straight into MP3 files. Details covers title, artist, album,
  album artist, genre, year, track/disc number, composer and comment, with an optional
  "rename file from tags" pattern. Artwork embeds a cover picked from the gallery or
  strips the existing one. Lyrics stores unsynced text (USLT) and includes a synced-LRC
  maker that stamps `[mm:ss.xx]` timestamps against live playback and exports a sidecar
  `.lrc`. Non-MP3 formats save to the library only.
- **Listening Journal** — tap the Poet mark in the header for a stats dashboard over
  your own archive: track / album / hour totals, heavy rotation (top track, most
  active artist, peak listening hour) counted from songs you actually listened to,
  and library health (tag integrity, missing cover art, paired `.lrc` files). The
  circular badge takes a portrait picked from the gallery.
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
│  MusicDatabase (SQLite): tracks, folders, playlists, plays, settings     │
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

## Credits

- Placeholder cover art: [Designed by rawpixel.com / Freepik](http://www.freepik.com).
  The original image was modified (cropped/resized) for in-app use, as permitted
  by the Freepik free license.

---

## Removing Blur & Glass Effects

**Reference:** Full analysis in `docs/BigPickleAi.md`.

### Files to Modify

| File | Changes |
|---|---|
| `app/src/main/assets/web/poet.css` | Remove `backdrop-filter` and `-webkit-backdrop-filter` from 7 selectors (`.center-shield`, `.menu`, `.lyrics-deck`, `.sheet-shield`, `#tray`, `.queue-shield`, `#modal-root .modal-shield`). Delete the dark-mode override block (lines 43–46). |
| `app/src/main/kotlin/com/musa/poetmusic/server/Shell.kt` | Update three glass variables (lines ~42–44) to opaque: `--card-glass: var(--card-bg)`, `--menu-glass: var(--card-bg)`, `--lyrics-glass: var(--card-bg)`. |

### Steps

1. In `poet.css`, delete every `backdrop-filter: blur(...)` and `-webkit-backdrop-filter: blur(...)` property.
2. In `poet.css`, delete the `html[data-theme="dark"] ... { backdrop-filter: none; ... }` block (lines 43–46).
3. In `Shell.kt`, replace the light-mode glass values with opaque equivalents matching `--card-bg` (`#ffffff` in light, `#1e1e24` in dark).
4. Optional: Reduce shield overlay opacity from `rgba(59,54,81,0.35)` to `~0.25` since blur no longer softens the dim.
5. Build and test in both light and dark mode. Verify no translucent backgrounds remain (they would look like transparency bugs).

---

## AI Review Instructions (Blur & Glass Effects)

When reviewing this codebase for blur/glass effects, an AI should:

1. **Search `poet.css`** for `backdrop-filter` and `-webkit-backdrop-filter`. List every selector, its blur radius, and line number.
2. **Search `Shell.kt`** for `--*-glass` variable definitions. List each variable, its light-mode value, dark-mode value, and which CSS selector consumes it.
3. **Cross-reference:** Every glass variable consumer must either have a `backdrop-filter` (light mode) or be opaque (dark mode). Flag any consumer that uses a translucent glass var without a blur.
4. **Verify the dark-mode override block** (poet.css lines 43–46) covers all blur selectors exactly once.
5. **Check server-rendered views** (`Views*.kt`) for any inline `style="backdrop-filter:..."` or `style="filter: blur(...)"`.
6. **Note known exceptions:** `#tip-shield` intentionally has no blur (poet.css:378–380). The `box-shadow` on the 10 `--accent-shadow` consumers is not a backdrop-filter leak; it read as a colored glow in dark mode until `--accent-shadow` was rethemed to a neutral there (see `docs/BigPickleAi.md` §11a).
7. **Report** findings in the format of `docs/BigPickleAi.md`.
