# Poet Music — Improvement Tasks

Missing features and gaps identified in the app review (comparison against
offline music players such as Musicolet). Ordered by priority and expected
user impact.

Legend: 🔴 high priority · 🟡 medium priority · 🟢 nice-to-have

---

## 1. 🔴 Equalizer

The single biggest expectation gap for a music player. Musicolet ships a
full 5-10 band graphic equalizer with presets.

**Work items:**
- Wire the Android `android.media.audiofx.Equalizer` (via
  `audioSessionId` from the ExoPlayer) or the Media3 `AudioEffects` API.
- UI: preset selector (Flat / Rock / Pop / Jazz / Classical / Dance / Custom)
  plus per-band sliders in a Settings card or Now Playing sheet.
- Persist the selected preset and custom bands in the settings table
  (`MusicDatabase.setSetting`).
- Apply the effect per audio session; handle session creation order
  (attach when playback starts) and release on service destroy.
- Optional: Bass Boost and Virtualizer effects alongside the EQ.

## 2. 🔴 Genre

Genre data is already stored (`tracks.genre`) and surfaced in the Journal,
but there is no way to browse by genre.

**Work items:**
- Add a **Genres** tab (or entry point) in `ViewsLibrary`:
  - New `MusicDatabase` query grouping tracks by genre (`SELECT genre,
    COUNT(*) FROM tracks WHERE genre <> '' GROUP BY genre`), mirroring the
    existing `albums()` / `artists()` helpers.
  - A genre detail screen listing its tracks, reusing `SharedViews.songList`
    with a `QueueCtx("genre", ...)` context (extend `QueueCtx` and
    `sourceLabel` in `PoetServer.kt`).

## 3. 🔴 Automatic / startup library rescan

Scanning is manual-only (`Settings → Trigger library scan`). Musicolet keeps
its library in sync without user intervention.

**Work items:**
- Rescan once on app/service start if the library is non-empty and the
  last scan is older than a configurable interval (e.g. every 12–24 h).
- Lightweight watchdog while the app is foregrounded: re-list each mapped
  folder's direct children periodically (e.g. every 60 s) and trigger a
  full scan only when the file list changed (new / removed / renamed files).
- Consider `registerContentObserver` on the SAF tree URIs where the
  provider supports it, as a cheaper event-driven alternative to polling.
- Surface scan activity in the UI without blocking navigation (the current
  `/partial/scan` polling pattern is already safe — extend, don't replace).
- Keep `deleteMissingInFolder` behaviour; consider journaling removed
  tracks instead of silently dropping their plays.

## 4. 🟡 Batch tag editing

The tag editor (`TagEditor.saveTags`, `ViewsTagEditor`) handles one track
at a time. Musicolet edits tags of multiple songs at once (e.g. set the
same artist on 20 files).

**Work items:**
- Extend the multi-select contextual action bar with **Edit tags**.
- Batch mode: a reduced form (title/artist/album/album-artist/genre/year/
  track-no) with a "leave unchanged" sentinel per field; empty submitted
  fields update all selected tracks, blank fields mean "keep".
- Apply the same field to every selected file, then bulk-update rows via
  `MusicDatabase.updateTags` per id.
- Non-MP3 files: library-only update (same rule as single-track editing).
- Playlist/queue metadata rebuild for renamed files (reuse
  `PlayerController.onTrackFileRenamed` per id).

## 5. 🟡 More sort options

Sorting exists for Songs only, with five states. Musicolet offers many
sort types across songs, albums, artists, genres.

**Work items:**
- Songs: expose the already-supported `date_added` ("Recently added",
  both directions) and `duration` keys (`MusicDatabase.tracks` already
  implements them — only the drawer's `SORT_STATES` omits them).
- Albums: by title, artist, year, track count.
- Artists: by name, track count.
- Genres (once added): by name, track count.
- Persist per-tab sort settings (currently a single `lib_sort` key).

## 6. 🟡 Share songs

Musicolet can share songs (and Now Playing screenshots) directly.

**Work items:**
- "Share" entry in the options drawer / sub-sheet for single-track
  selections: `FileProvider` (new manifest provider + `file_paths.xml`)
  exposing the SAF document via `contentResolver.openInputStream` +
  `Intent.ACTION_SEND` with the track's mime type.
- Optional: share the now-playing artwork/lyrics screenshot (render the
  view to a bitmap in the WebView, save to cache, share).

## 7. 🟢 Full-screen album art

Musicolet zooms any album art to full screen and can save it to the gallery.

**Work items:**
- Tap the Now Playing cover (or an album card cover) to open a full-screen
  art viewer (dimmed backdrop, image, tap to dismiss — reuse the existing
  overlay patterns and `body.overlay-open` lock).
- "Save to gallery" action: write the art bytes to
  `MediaStore.Images` (`INSERT into MediaStore.Images.Media` with
  `RELATIVE_PATH = Pictures/Poet`), requesting the WRITE permission only
  at that moment.

## 8. 🟢 Previous-button behaviour option

`PlayerController.previous()` hardcodes a 3 s restart threshold.

**Work items:**
- Add a setting ("Restart current song if played more than x seconds",
  e.g. 3 s / 5 s / always previous) read in `previous()`.

## 9. 🟢 Android Auto

Musicolet fully supports Android Auto (whole library browsable).

**Work items:**
- Media3 `MediaLibraryService` exposes the library tree (songs / albums /
  artists / playlists / favorites) to Android Auto, replacing or wrapping
  the current `MediaSessionService`.
- This is a large feature; only pursue after the browsable queue work
  (§6) lands, since both share the media-tree plumbing.

---

## Cross-cutting quality items

- **🔴 Large-library performance:** `SharedViews.songList` renders every
  row server-side on each filter/sort keystroke. Add pagination or
  server-side windowing (limit/offset with `Load more` or infinite scroll)
  and keep the DOM row count bounded. Target: smooth at 10k+ tracks.
- **🟡 Test coverage:** no instrumentation/UI tests exist. At minimum add
  a smoke test (launch → library loads → play/pause → queue ops) plus a
  Robolectric test for the player controller's queue model (shuffle /
  unshuffle / play-next ordering), since `docs/Bugs.md` shows this area
  regresses.
- **🟡 Release build:** `isMinifyEnabled = false` is a documented
  trade-off. Before any store release, flip minification on with the
  existing proguard rules and validate Ktor/Media3 reflection paths on a
  device.