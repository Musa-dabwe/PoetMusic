# Poet Music — Linux desktop app build plan

Turn the `.deb` from a status page that opens a browser tab into a
**self-contained native Linux application** with the full Poet UI and working
local-file playback.

**Status: built.** Every 🔴 and 🟡 item below is implemented and verified on
this machine; §6 is the deliberate remainder. The document is kept as written
so the decisions and their reasoning stay readable next to the code.

Companion to `docs/native-ui-solidification.md` §5, which decided *that* the
port happens and did the groundwork (`:core` / `:desktop` split, JDBC store,
nio scanner, `jpackage` wiring). This document is the build-out: it answers the
questions §5.4 left open and lists the work to a finished app.

Design spec: `docs/mockups/layout-modes.html`, PC compact / PC wide modes.
Those layouts are **already implemented** in `app/src/main/assets/web/poet.css`
(the five-tier breakpoint block at the foot of the file) and the desktop serves
that exact file, so no CSS work is in scope here. The window just has to open
at a PC-tier size and the layouts appear.

Legend: 🔴 blocking · 🟡 needed for parity · 🟢 polish

---

## 0. What exists before this plan

| Piece | State |
| --- | --- |
| `:core` | `Models`, `MimeTypes`, `Html`, `Markdown`, `EqPresets` only |
| `:desktop` | Ktor CIO on loopback, JDBC store (tracks/folders/settings), nio scanner |
| Frontend | Whole htmx SPA, PC breakpoints included, served from `app/src/main/assets` |
| Shell | `xdg-open` → **a browser tab**, which is the thing being fixed |
| Playback | none |
| View layer | none — the desktop serves a hand-written status page |
| `.deb` | builds, installs, runs; bundles its own JRE via `jpackage` |

The blocker §5 named is real: the entire view layer (`Views*.kt`, `Shell.kt`,
~1,800 lines) is written against `MusicDatabase` (an Android
`SQLiteOpenHelper`) and `PlayerController` (an Android `object` over ExoPlayer).
Neither can move to `:core` as-is, and duplicating them for the desktop would
guarantee drift.

---

## 1. 🔴 Decisions — closing §5.4

§5.4 left three questions open. All three are now answered, with the evidence
that settled them.

### 1.1 Shell — GTK 3 + WebKitGTK over JNA

**Chosen: bind the system's GTK 3 and WebKitGTK through JNA.**

| Option | Verdict |
| --- | --- |
| Default browser (`xdg-open`) | What ships today. A tab, not an app. Rejected. |
| JavaFX WebView | ~90 MB of bundled WebKit in the `.deb`, and an older WebKit than the machine already has. Rejected. |
| JCEF | Larger still, for the same result. Rejected. |
| **GTK 3 + WebKitGTK via JNA** | **Chosen.** ~120 lines of bindings, nothing native bundled, and a current WebKit. |

Verified on this machine: `libgtk-3.so.0` (GTK 3.24.49) and
`libwebkit2gtk-4.1.so.0` (WebKit 2.48) are both present and provided by
`libgtk-3-0t64` and `libwebkit2gtk-4.1-0`, which are on every Debian/Ubuntu
desktop install. A spike opened a real window and rendered a page.

Fallback: if neither `webkit2gtk-4.1` nor `-4.0` loads, the app opens the
default browser instead of failing to start.

### 1.2 Playback — GStreamer over `gst1-java-core`

**Chosen: GStreamer `playbin`, bound with `gst1-java-core` 1.4.0.**

VLCJ would have been less assembly, but drags in `libvlc` — a second media
stack on a machine that already has GStreamer, and a heavier dependency for a
`.deb`. JavaFX `MediaPlayer` cannot play FLAC, OGG or Opus, which the library
here contains.

Verified on this machine: GStreamer 1.24.2, `gst1-java-core` 1.4.0 plays an
`.m4a` from `~/Music`, and both elements the parity work needs exist —
`equalizer-10bands` (plugins-good) for the EQ card and `pitch` (plugins-bad,
soundtouch) for the speed chip.

### 1.3 Distribution — bare `.deb`

Unchanged from §5.4: a `.deb` only, no Flatpak. `jpackage --type deb` over a
`jlink`ed runtime, so the installed app has no Java dependency.

---

## 2. 🔴 Share the view layer — `:core` extraction

The heart of the work. Everything that renders HTML or routes a request must
move to `:core` and stop naming Android types. The rule `:core` already
enforces (no Android on the compile classpath) means a mistake here fails the
build rather than quietly re-coupling the port.

### 2.1 🔴 Define the platform seams as interfaces in `:core`

Each one replaces a concrete Android type the views currently name.

| Interface | Replaces | Android impl | Desktop impl |
| --- | --- | --- | --- |
| `LibraryStore` | `MusicDatabase` | `MusicDatabase` implements it directly | `DesktopLibrary` (JDBC) |
| `PlayerPort` | `PlayerController` | adapter over the existing object | `GstPlayer` |
| `EqPort` | `AudioFx` | adapter over the existing object | `GstEq` |
| `ScanPort` | `LibraryScanner` | adapter | `DesktopScanner` |
| `TagPort` | `TagEditor` | adapter | `DesktopTags` (JAudiotagger) |
| `HostPort` | the `*Requester` lambdas, `MediaMetadataRetriever`, SAF file ops | `AndroidHost` | `DesktopHost` |

`PlayerSnapshot` and `QueueItem` move out of `PlayerController` into `:core`
alongside the repeat/shuffle constants, because the views render them.

**Work items:**
- Write the six interfaces with exactly the methods the views and routes call —
  no more. The Android side has to keep satisfying them unchanged.
- Move `LyricLine` / `LrcParser` to `:core`, parsing text rather than reading a
  `Uri`; reading bytes for a track is a `HostPort` job.
- Give `:core` an `AppInfo` holder for version and platform strings, replacing
  the `BuildConfig.VERSION_NAME` the About screen reads.

### 2.2 🔴 Move the view layer to `:core`

**Work items:**
- Move `Shell.kt`, `ViewsShared.kt`, `ViewsLibrary.kt`, `ViewsNowPlaying.kt`,
  `ViewsQueue.kt`, `ViewsDrawer.kt`, `ViewsSettings.kt`, `ViewsJournal.kt`,
  `ViewsTagEditor.kt` and `LibrarySort.kt` from `:app` to `:core`.
- Retarget every `MusicDatabase` parameter to `LibraryStore`, every
  `PlayerController` reference to an injected `PlayerPort`, every `AudioFx`
  reference to `EqPort`, every `LibraryScanner` reference to `ScanPort`.
- The About screen's prose is Android-specific ("Minimum Android 8.0",
  "Media3 / ExoPlayer", "native WebView"). Parameterise it so each platform
  states its own stack truthfully.

### 2.3 🔴 Move the routes to `:core`

`PoetServer.kt` is 929 lines and only a handful of them are Android.

**Work items:**
- Extract the routing table into `:core` as `poetRoutes(deps)` over a `PoetDeps`
  bundle of the six ports.
- Keep the art LRU cache with the routes — it is platform-independent, bounded
  by bytes, and both builds want it.
- Leave `:app`'s `PoetServer` as a thin adapter: build the Android
  implementations, hand them over, bind `127.0.0.1:8080`.
- The `127.0.0.1`-only bind is load-bearing on both platforms (README, About
  screen, SECURITY.md). Desktop keeps the OS-assigned port from §5.

### 2.4 🟡 Verification

- `:app` still builds and the APK behaves identically — this refactor must be
  behaviour-neutral on the phone.
- `:core` has no `android.*` import anywhere (the module boundary proves it).

---

## 3. 🔴 Desktop implementations

### 3.1 🔴 `DesktopLibrary` — the full store

Today's `LibraryStore` class covers tracks, folders and settings. The view
layer needs the rest.

**Work items:**
- Complete the schema: `playlists`, `playlist_tracks`, `plays`, column-for-column
  with the Android one, so a library could be copied between them.
- Implement every `LibraryStore` method: sorts, search + paging, albums /
  artists / genres grouping, playlists, favourites, `journalStats()` and its
  streak / format-share reads.
- The Android SQL is portable — SQLite is SQLite. Port the query strings rather
  than rewriting them, so the two stores answer identically.
- Use a single connection with `busy_timeout`; the desktop has no WAL-pool
  requirement the phone has, but scans and reads still overlap.

### 3.2 🔴 `GstPlayer` — playback

**Work items:**
- `playbin` with an `audio-filter` bin of `equalizer-10bands` → `pitch`, so the
  EQ card and the speed chip both have something to drive.
- Implement the whole `PlayerPort` queue model — it is Musicolet-style and lives
  in the controller, not in ExoPlayer: a static literal sequence, `masterIds`
  for un-shuffling, play-next insertion after the current item.
- Drive transitions off the bus `EOS` signal: advance, honour the repeat mode,
  decrement the after-N-songs sleep timer.
- Keep the snapshot refresher on a scheduled executor at the same 500 ms the
  phone uses, including the wall-clock sleep deadline, the 20 s play threshold
  that writes the journal's plays log, and the every-3 s state persistence.
- Speed changes go to `pitch`'s `tempo` property, not to a seek rate, so pitch
  is preserved the way ExoPlayer's `setPlaybackSpeed` does.

### 3.3 🔴 `DesktopScanner` + `DesktopTags` — library and tags

**Work items:**
- Scanner: `java.nio` walk, JAudiotagger for title / artist / album / duration /
  track / genre / year / album artist / disc / composer / artwork presence,
  `.lrc` sidecar pairing by base name, and `deleteMissingInFolder` for files
  that have gone.
- Report progress into the same `isScanning` / `progressText` the Settings scan
  card polls.
- Tags: JAudiotagger read and write. This is a **capability gain** over the
  phone, whose hand-rolled writer is MP3-only — FLAC, OGG and M4A become
  editable. Cover art embedding and removal go through the same path.
- Keep the file-rename-from-pattern behaviour, over `java.nio.file.Files.move`
  instead of SAF, and repoint the track row at the new path.

### 3.4 🔴 `DesktopHost` — everything else the routes need

**Work items:**
- Album art: JAudiotagger artwork extraction, feeding the routes' existing LRU.
- Audio streaming for `/api/stream/{id}` (kept for parity even though GStreamer
  reads the file directly).
- File size, delete-from-disk, and the `.lrc` sidecar write.
- Folder picker: GTK's own file chooser through the same JNA binding as the
  window — no second toolkit, and it looks native.
- Image picker for cover art and the journal portrait, same chooser.
- The Android-only hooks (widget pinning, share sheet, save-to-gallery) answer
  "unavailable on desktop" through the existing toast path, which is what the
  routes already do when a requester is null.

### 3.5 🟡 `GstEq` — the equalizer card

**Work items:**
- Map `EqPresets`' five anchor frequencies onto `equalizer-10bands`' fixed ten
  bands via the existing log-interpolation, so a preset means the same thing on
  both platforms.
- Bass boost and virtualizer have no direct GStreamer equivalent; report them
  unavailable and let the card's existing "not offered by this device" path
  handle it, rather than faking them.

---

## 4. 🔴 The native window

**Work items:**
- `WebKitWindow`: GTK 3 window + WebKit view over JNA, opened at 1280×820 so
  the first frame is the PC-compact tier rather than the phone column.
- Hold JNA callback references on the Kotlin side — a collected callback is a
  native crash.
- GTK owns the process's first thread: start Ktor first, then block on
  `gtk_main()`.
- Closing the window shuts the app down cleanly: stop playback, persist state,
  stop the server.
- Fall back to the default browser when WebKitGTK is absent.

---

## 5. 🟡 Packaging

**Work items:**
- Declare the real runtime dependencies now that they are known:
  `libgtk-3-0 | libgtk-3-0t64`, `libwebkit2gtk-4.1-0 | libwebkit2gtk-4.0-37`,
  `libgstreamer1.0-0`, `gstreamer1.0-plugins-base`, `-good`, `-bad`
  (soundtouch, for the speed chip), `gstreamer1.0-libav`.
- Keep the `.desktop` entry, icon and menu category `jpackage` already emits.
- Verify with `lintian`; the remaining findings are inherent to `jpackage`
  vendor packages (`dir-or-file-in-opt`, no changelog, unstripped JDK
  binaries) and only matter for a Debian archive submission.
- Ship the built `.deb` to the desktop, replacing the previous one.

---

## 5b. Verified

- 249 tracks scanned from a real `~/Music` with full tags, album art, durations
  and genres; 207 albums grouped; the Journal's totals, leaderboards and
  tag-health numbers all render.
- Playback confirmed end to end: play, position advancing, duration, seek,
  queue, and the transport bar reflecting the playing track.
- The window is a real GTK window titled "Poet Music", opening at the
  PC-compact tier with the nav rail, the library, the Settings screen and its
  ten-band equalizer, and Now Playing in the mockup's tablet treatment.
- An upgrade from the interim prototype database keeps its folders, accent,
  canvas tint and dark-mode preference (the `settings(k,v)` → `settings(key,
  value)` rebuild in `DesktopLibrary`).
- `:app` still assembles and is behaviour-neutral: the Android build now gets
  its views and routes from `:core` and nothing else about it changed.

---

## 5c. ✅ MPRIS, and what the package says about itself

Landed after the fact; kept here rather than in §6 because it closes that
section's first item.

**MPRIS over D-Bus** (`desktop/.../Mpris.kt`). `org.mpris.MediaPlayer2` and
`.Player` on one object at `/org/mpris/MediaPlayer2`, with every readable value
behind `org.freedesktop.DBus.Properties` — which is the whole protocol, and the
only thing GNOME's panel, KDE's applet, the lock screens and the `XF86Audio`
keys all agree on.

| Decision | Why |
| --- | --- |
| `dbus-java` over hand-rolled GDBus through JNA | The transport is the JDK's own Unix-domain socket support, so the `.deb` gains no native dependency. Marshalling `a{sv}` metadata dictionaries by hand over GVariant is several hundred lines of JNA that would then have to be maintained. |
| Poll the player's snapshot on the existing 500 ms cadence | `GstPlayer` already refreshes on that tick and `snapshot` is safe to read from any thread, so MPRIS needs no listener plumbing inside the player. `PropertiesChanged` carries only the properties that actually moved. |
| `Position` never signalled | The spec asks for it — a playing track would otherwise be a signal every tick. Clients poll it. |
| Cover art staged in `$XDG_CACHE_HOME` | MPRIS hands a shell a URL, not bytes. Keyed by the file's mtime so a tag edit produces a new URL rather than a stale image, and pruned to the last few. |
| Quit halts the process | Two bugs, both found live. GTK silently drops a close request for a window it has not realized yet, so a `Quit` in the first second did nothing; and returning from `main` hands the process to GStreamer's and WebKitGTK's native teardown, which aborts — `EXIT=134`, a core dump and a desktop crash report for a clean quit. Both exit paths now flush our own state and `Runtime.halt(0)`. |

`GstPlayer` gained direct setters (repeat, shuffle, speed, volume, play, pause,
stop, relative seek) beside its existing advance/cycle ones, because a D-Bus
client writes the value it wants rather than cycling to the next one.

**Uninstalling now stops playback.** `dpkg` unlinks the files, the running
process keeps its own inodes open, and the music carries on with no window left
to stop it from. `packaging/jpackage/prerm` signals every process running out of
the install directory and waits for it, so the JVM shutdown hook saves the queue
first. It reads `/proc/PID/exe` rather than calling `pgrep`: `procps` is not
Essential and a maintainer script may not assume a package it has not declared.

**And the package now says who made it.** It claimed
`Poet Music <poet-music@noreply.invalid>`, so a software centre with nothing
better captioned it "poet-music Developers". `--vendor`, `--about-url` and a
custom `control` template fix the Debian side; the author name a software centre
actually reads lives in AppStream, so `packaging/metainfo/` ships a
`metainfo.xml` that `postinst` copies to `/usr/share/metainfo` and `postrm`
removes. `jpackage` on JDK 17 cannot place a file outside the app directory,
which is why it travels inside the package and is copied rather than installed.

Every one of these overrides jpackage's own template through `--resource-dir`.
Custom resources are token-substituted exactly as the stock ones are —
`APPLICATION_PACKAGE`, `UTILITY_SCRIPTS`, `DESKTOP_COMMANDS_UNINSTALL` — which
is verified by unpacking the built `.deb`, and is worth knowing because the
substitution also rewrites those words inside comments.

---

## 6. 🟢 Follow-ups, deliberately out of scope here

- **`WatchService` folder watching** — the desktop counterpart of
  `LibraryWatcher`. Manual and interval rescans cover the gap.
- **Now Playing as a permanent right-hand pane** — `native-ui-solidification.md`
  §4's outstanding item, a CSS/markup change shared with the Android build.
- **Window size and position persistence.**
- **Multi-window / tray icon.**
