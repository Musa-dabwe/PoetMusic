# Poet Music — Improvement Tasks

Missing features and gaps identified in the app review (comparison against
offline music players such as Musicolet). Ordered by priority and expected
user impact.

**Status — reconciled with the code on 2026-08-07.** Eight of the nine
original items have landed (see "Completed" at the bottom, with the file
that carries each one). What is genuinely left is listed first.

Legend: 🔴 high priority · 🟡 medium priority · 🟢 nice-to-have

---

## Pending

### 1. 🟢 Android Auto

Musicolet fully supports Android Auto (whole library browsable).

**Work items:**
- Media3 `MediaLibraryService` exposes the library tree (songs / albums /
  artists / playlists / favorites) to Android Auto, replacing or wrapping
  the current `MediaSessionService`.
- This is a large feature; only pursue after the browsable queue work
  lands, since both share the media-tree plumbing.

### 2. 🟡 Test coverage (cross-cutting)

No instrumentation/UI tests exist (`app/src/androidTest` is absent). At
minimum add a smoke test (launch → library loads → play/pause → queue ops)
plus a Robolectric test for the player controller's queue model (shuffle /
unshuffle / play-next ordering), since `docs/Bugs.md` shows this area
regresses.

### 3. 🟡 Release build minification (cross-cutting)

`isMinifyEnabled = false` is a documented trade-off (Ktor/CIO and Media3
resolve by reflection, and there is no on-device test pass to catch a bad
shrink). Before any store release, flip minification on with the existing
proguard rules and validate the reflection paths on a device.

---

## Completed

Verified against the code on 2026-08-07; kept as a record of what the
review asked for and where it landed.

- 🔴 **Equalizer** — ten-band EQ with presets on both platforms:
  `playback/AudioFx.kt` (Android), `desktop/GstEq.kt`, shared presets in
  `core/.../playback/EqPresets.kt`, port in `EqPort`.
- 🔴 **Genre browsing** — Genres tab with detail screens and sorting:
  `genresTab` in `core/.../server/ViewsLibrary.kt`.
- 🔴 **Automatic / startup library rescan** — `data/LibraryWatcher.kt`
  (ContentObserver plus a polling fallback while foregrounded) and
  `scanner.maybeAutoScan()` on startup (both platforms).
- 🟡 **Batch tag editing** — multi-select "Edit tags" sheet with
  leave-unchanged sentinels: `core/.../data/BatchTagForm.kt`, route
  `/api/library/batch-tags` in `PoetRoutes.kt`, pinned by
  `BatchTagFormTest`.
- 🟡 **More sort options** — per-tab sort drawer on every tab (Songs alone
  has nine orders, including Recently added and duration), each tab's order
  persisted separately: `core/.../server/LibrarySort.kt`, pinned by
  `LibrarySortTest`.
- 🟡 **Share songs** — single and multiple tracks via
  `ACTION_SEND` / `ACTION_SEND_MULTIPLE` in `MainActivity.kt`, surfaced
  through `HostPort.shareRequester` (desktop passes null).
- 🟢 **Full-screen album art** — tap-to-zoom viewer with dismiss (the
  "full-screen album art" section of `assets/web/poet.js`), plus save to
  gallery via `MediaStore.Images` under `Pictures/Poet` in
  `MainActivity.kt`.
- 🟢 **Previous-button behaviour option** — persisted `prev_restart_ms`
  setting, clamped to the range the Settings card offers, read by
  `PlayerController.previous()`.
- 🔴 **Large-library performance** — server-side windowing with
  `PAGE_SIZE = 60` and SQL limit/offset for the songs tab in
  `core/.../server/ViewsShared.kt`, pinned by `SongListPagingTest`.
