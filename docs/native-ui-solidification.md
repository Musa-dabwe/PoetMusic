# Poet Music — Native UI Solidification

Layout and responsiveness work: make Now Playing fit any screen without
scrolling, and adapt the whole frontend to landscape, tablet and (later)
desktop windows, linux and mac. The UI is an htmx SPA rendered in a WebView, so every
"screen size" concern is a CSS/HTML concern — with one native-side exception
(display cutout, §3.1).

Legend: 🔴 high priority · 🟡 medium priority · 🟢 nice-to-have

**Ordering:** §1 → §2 (mockup) → §3/§4 (implementation) → §5 (desktop).
§2 is a hard gate: no layout code lands before the mockup is reviewed and
approved.

**Status — 15 of 19 sub-sections complete, 3 partial, 1 not started.**
§5 is finished: the Linux desktop build is a real native app now, and the
build-out is written up in `docs/desktop-app-plan.md`.
Markers: ✅ done · ◐ partial · ⬜ not started.

| §   | State | Note |
| --- | --- | --- |
| 1.1 | ✅ | `body.np-open`, cleared on every other swap |
| 1.2 | ✅ | flexible art, one-line chips, height + width control tiers |
| 1.3 | ✅ | deck is the only scroller; `poetToggleLyrics` moved to `poet.js` |
| 1.4 | ✅ | tray hidden on the route modes via `--tray-h`, kept on PC |
| 1.5 | ◐ | headless-verified 360×640/800, 800×360, 834×1112, 1280×800; **no device test yet** |
| 2.1–2.6 | ✅ | mockup built and decisions locked |
| 3.1 | ◐ | cutout mode + window background done; full edge-to-edge deferred (see below) |
| 3.2 | ✅ | `--shell-w` replaces all seven copies of the 480px cap |
| 3.3 | ✅ | five-tier block at the foot of `poet.css`; portrait-only viewport maths audited |
| 3.4 | ◐ | rail, two-column lists, art-on-top done; **inline tag editor outstanding** |
| 4   | ◐ | rail, hover/focus-visible, keyboard shortcuts, desktop text selection done; **permanent NP pane outstanding** |
| 5.1–5.2 | ✅ | portability analysis confirmed against the code |
| 5.3 | ✅ | whole view layer + routes shared from `:core`; JDBC store, GStreamer playback, JAudiotagger tags, GTK/WebKit window, `.deb` |
| 5.4 | ✅ | shell = GTK 3 + WebKitGTK over JNA; playback = GStreamer; distribution = bare `.deb` |
| 6   | ⬜ | follow-ups, 🟢 priority |

**Deferred deliberately:** full edge-to-edge (§3.1). The black band is fixed by
`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` plus a window background that
tracks the canvas, which keeps the system insetting the content. Going
`decorFitsSystemWindows(false)` as well would put the header under the status
bar if the WebView does not report `env(safe-area-inset-top)`, and that cannot
be verified without a device. Revisit with hardware in hand.

---

## Current state (for reference)

| Concern | Where it lives today |
| --- | --- |
| Shell / header / tray markup | `server/Shell.kt:78-148` |
| App width cap | `assets/web/poet.css:80` — `#app { max-width:480px }` |
| Screen padding | `assets/web/poet.css:81` — `#main-container { padding:6px 20px 130px 20px }` |
| Now Playing markup | `server/ViewsNowPlaying.kt:61-137` |
| Cover art sizing | `assets/web/poet.css:231` — `.np-art { width:min(78vw,320px) }` |
| Lyrics deck | `assets/web/poet.css:246` — `.lyrics-deck { max-height:38vh }` |
| Lyrics toggle | `server/ViewsNowPlaying.kt:126-136` — inline `toggleLyrics()` |
| Media tray | `assets/web/poet.css:546` — fixed, `max-width:480px` |
| Scroll lock precedent | `assets/web/poet.css:68` — `body.overlay-open { overflow:hidden }` |
| Viewport meta | `server/Shell.kt:34` — `viewport-fit=cover` already set |
| Activity config | `AndroidManifest.xml:34` — handles `orientation\|screenSize\|screenLayout` itself |

---

## 1. ✅ 🔴 Now Playing: no scrolling unless lyrics are open

The screen is a plain vertical stack inside the scrolling `#main-container`.
On short screens the chip row (speed / sleep / lyrics / favourite / queue)
falls below the fold and the whole page scrolls, which also drags the fixed
media tray over the controls. Target: **Now Playing never scrolls; the lyrics
deck is the only scroller, and only while lyrics are toggled on.**

### 1.1 ✅ Lock the page scroller on Now Playing

**Work items:**
- Add a `body.np-open` (or `data-screen="now-playing"` on `.screen`) flag set
  when the Now Playing screen swaps in, cleared on every other screen swap.
  Reuse the existing mechanism rather than inventing a second one — see how
  `body.journal-open` is applied in `poet.js` and consumed at
  `poet.css:254-256`.
- Under that flag: `#main-container { overflow:hidden; padding-bottom:0 }`
  and make the Now Playing screen a fixed-height flex column sized to the
  visible viewport minus the header and tray, not `min-height`.
- Do **not** reuse `body.overlay-open` (`poet.css:68`) — that is owned by the
  overlay MutationObserver in `poet.js:40-52` and would be clobbered when a
  drawer opens over Now Playing.
- Verify the back-navigation path clears the flag (htmx `hx-target`
  `#main-container` swaps, plus the hardware-back handler in `MainActivity`).

### 1.2 ✅ Make the stack fit instead of overflow

The stack is: back link → cover art → title → artist → seek bar → transport
row → chip row → lyrics deck. Everything except the deck must be visible at
once on the shortest supported screen.

**Work items:**
- Size the screen with `100dvh` (dynamic viewport) rather than `100vh`, so a
  retracting browser/system bar does not create phantom scroll. Keep a
  `100vh` fallback first for older WebViews.
- Make the cover art the flexible element — it should absorb the leftover
  space instead of forcing overflow. Replace the fixed
  `width:min(78vw,320px)` (`poet.css:231`) with a height-driven clamp, e.g.
  `flex:1 1 auto; min-height:0; max-height:<remaining>; aspect-ratio:1;
  width:auto`, so it shrinks first and stays square.
- Give the controls (`.np-side`, `.np-main`, `.np-dot`, `.chip`) a compact
  tier via container/viewport queries so they step down on short screens
  rather than wrapping. Suggested tiers (tune against real devices):
  - `height >= 780px` — current sizes (76px main, 52px side, 44px dot).
  - `600px–780px` — main 64px, side 46px, dot 38px, chip padding `7px 13px`,
    tighter gaps and margins.
  - `< 600px` (and landscape phones) — main 56px, side 42px, dot 34px, chips
    `6px 11px` at 12px, title 18px / artist 13px.
- Stop the chip row from wrapping into a second line at small widths: allow a
  single horizontally-scrollable row (`overflow-x:auto`, hidden scrollbar,
  the pattern already used by `.journal-albums` at `poet.css:301`) as the
  last-resort fallback, so all five chips stay reachable without the page
  growing.
- Trim vertical margins that are currently hard-coded inline in
  `ViewsNowPlaying.kt:89-119` (`margin-bottom:20px`, `margin-top:18px`,
  `margin-top:22px`) — move them to CSS classes so the tiers above can
  actually override them.
- Reserve the safe-area insets explicitly: the bottom padding must account
  for the tray height plus `env(safe-area-inset-bottom)`.

### 1.3 ✅ Lyrics: the one place scrolling is allowed

**Work items:**
- When lyrics are toggled on, the deck takes the space the cover art gives
  up: shrink `.np-art` (or hide it entirely on short screens) and let
  `.lyrics-deck` grow into a `flex:1; min-height:0; overflow-y:auto` panel
  instead of today's fixed `max-height:38vh` (`poet.css:246`).
- Keep `overscroll-behavior:contain` on the deck so scroll never chains into
  the (now frozen) page.
- Move `toggleLyrics()` out of the inline `<script>` in
  `ViewsNowPlaying.kt:125-137` into `poet.js` — it has to coordinate with the
  layout classes now, and re-declaring a function on every screen swap is
  already fragile.
- The toggle must add/remove a class on the Now Playing root (e.g.
  `.np-lyrics-on`) so the CSS drives the whole layout change; don't animate
  by mutating inline styles (`deck.parentElement.style.display` today).
- Confirm the auto-scroll math in `poet.js:424-431` (which scrolls only the
  deck box) still centres the active line after the deck becomes flexible.

### 1.4 ✅ Hide the mini player while Now Playing is a route

Locked by the mockup review. The media tray duplicates the transport controls
sitting directly above it and costs ~68px of exactly the height §1.2 is
fighting for.

**Work items:**
- Hide `#tray` (`poet.css:546`) whenever the Now Playing screen is showing
  **and** Now Playing is a full route — i.e. portrait mobile, landscape
  mobile and tablet.
- Do it by zeroing the tray-height variable rather than `display:none` alone,
  so every rule that reserves space for the tray reclaims it in one step.
  Introduce `--tray-h` alongside `--shell-w` (§3.2) and have
  `#main-container`, `.np` and the toast offset read from it instead of the
  hard-coded `130px` / `120px` / `96px` they use today
  (`poet.css:81,257,601`).
- **Do not** hide it on the PC tiers: there Now Playing is a permanent pane
  and the transport bar *is* the player (§2.4). The rule needs an explicit
  counter-rule at the PC breakpoints, not just a narrower media query.
- The queue screen keeps the tray on every mode — the queue slides over the
  library, it does not replace the player.

### 1.5 ◐ Verification

- Now Playing on a 360×640 phone: cover art, seek bar, transport row and all
  five chips visible; page does not scroll; tray does not overlap.
- Same screen with lyrics on: deck scrolls, page does not.
- Rotate to landscape mid-playback (§3) and back — no stuck scroll lock, no
  clipped controls.
- Open the queue panel, sleep drawer and full-screen art viewer from Now
  Playing — each still locks/unlocks correctly with the new flag in play.

---

## 2. ✅ Landscape / tablet / desktop mockup — **before any code**

Deliverable: a standalone HTML mockup (static, no htmx, no server) checked
into `docs/mockups/`, showing every layout mode with real-looking content.
No layout code in `poet.css`, `Shell.kt` or the `Views*.kt` files changes
until this is reviewed and approved.

**Built:** `docs/mockups/layout-modes.html`. Open it over http (e.g.
`python3 -m http.server` from the repo root) so the Outfit font resolves;
over `file://` Chrome blocks it and the system font stack takes over.
The settled outcome is §2.6.

### 2.1 Mockup mechanics

**Work items:**
- Create `docs/mockups/layout-modes.html`: one self-contained file, inlining
  the current design tokens (the `:root` block from `Shell.kt:42-74` plus the
  dark overrides from `poet.css:10-46`) so colours match the shipping app.
- Include a mode switcher (Portrait mobile / Landscape mobile / PC compact /
  PC wide) that swaps a class on a frame element, plus a light/dark toggle —
  reviewable in a desktop browser without resizing the window.
- Use fixed-size preview frames per mode so the mockup is honest about the
  target: 360×800 portrait, 800×360 and 915×412 landscape, 1280×800 and
  1920×1080 desktop.
- Mock every screen that changes shape: Library (Songs/Albums/Artists/
  Genres/Playlists), Now Playing (with and without lyrics), Queue panel,
  Settings, Listening Journal, About, Tag editor.
- Placeholder art only — CSS gradients or the existing `placeholder.jpg`
  pattern. No new binary assets.

### 2.2 Mode A — portrait mobile (baseline, unchanged)

**Work items:**
- Reproduce the current layout as-is so the other modes can be diffed
  against it: 480px column, header on top, fixed media tray at the bottom,
  bottom sheets and drawers.
- This is the reference; any change here is a regression unless deliberate.

### 2.3 Mode B — landscape mobile

The constraint is height (~360–420px), not width. Stacked layouts fail.

**Work items:**
- Split the canvas into two columns instead of one narrow centred one — see
  §3 for why the current `max-width:480px` is the root problem.
- Now Playing in landscape: cover art on the left column, metadata + seek +
  transport + chips in the right column; lyrics replace the right column (or
  slide in as a third pane) when toggled.
- Library in landscape: tab pills as a compact top bar or a slim left rail;
  the song list in one or two columns depending on width.
- Media tray: keep it bottom-fixed but shorter, or dock it to the bottom of
  the content column — decide in the mockup, not in code.
- Bottom sheets (sort drawer, sleep timer, options drawer, tag editor) are
  the most fragile pieces in landscape — today they are `max-height:88-92vh`
  bottom sheets (`poet.css:150,373,445`). Mock them as **side sheets** or
  centred cards in landscape.
- Respect the display cutout: the mockup must show a safe-area gutter on the
  cutout edge, matching the fix in §3.1.

### 2.4 Mode C — PC / desktop (two variants)

Groove Music-style: one window, everything reachable, no full-screen
navigation for secondary screens. Two variants as requested:

**PC compact (≈1000–1400px wide) — work items:**
- Persistent left navigation rail (Poet mark, Library tabs, Playlists,
  Favourites, Settings) replacing the header pills from `Shell.kt:80-89`.
- Main content pane: the library list/grid, scrolling independently of the
  rail.
- Persistent bottom transport bar spanning the full window (an evolution of
  `#tray`), with the seek bar inline — not a separate Now Playing screen for
  basic control.
- Now Playing as a right-hand pane or an expandable panel, not a route that
  replaces the library.
- Queue as a right-side dockable panel.

**PC wide (≥1400px) — work items:**
- Three-column layout: nav rail · library · Now Playing + queue, all visible
  simultaneously.
- Album/artist grids widen with more columns rather than stretching cards.
- Cap line lengths on text-heavy surfaces (About, Journal narrative) so they
  stay readable at 1920px.

**Card-panel modals (both PC variants) — work items:**
- Listening Journal and About render as centred card modals over the shell,
  not full-screen takeovers. The Journal already has a full-screen mode
  (`body.journal-open`, `poet.css:254-257`); the mockup must show the
  modal variant so the eventual implementation can branch on mode.
- Same treatment for: Settings (or keep as a rail destination — decide in
  the mockup), Tag editor (already a sheet → becomes a centred dialog), the
  sort drawer (→ dropdown/popover), the sleep timer (→ small dialog).
- Show a consistent modal chrome: card, dim shield, close affordance,
  Escape-to-close.

### 2.5 Mockup review checklist

- Every UI element that exists in portrait has a home in every other mode —
  nothing silently dropped.
- Touch targets stay ≥44px in both mobile modes; PC modes may go denser.
- Light and dark both look right in every mode.
- Breakpoint values are written down in the mockup and are the ones §3 will
  implement — no re-deriving them later.

### 2.6 Decisions locked by the mockup

`docs/mockups/layout-modes.html` is built and reviewed. Everything below is
settled — §3 implements it rather than re-deciding it. The mockup drives the
layout with `@container shell (…)` queries on a size-contained frame, so the
rules port to `poet.css` as media queries unchanged; that is the intended
mechanism, not a mockup-only trick.

**Shell**
- One `--shell-w` custom property replaces the seven copies of the 480px cap.
  One `--tray-h` replaces the hard-coded tray offsets (§1.4).
- The nav rail and the header pills both live in the DOM at all times;
  CSS decides which renders. Same for the Journal's modal vs. full-screen
  chrome. No server-side branching, no `Views*.kt` layout logic.
- Rail progression: hidden (portrait) → 56px icon rail (landscape) → 210px
  labelled rail (tablet) → 224px (PC compact) → 250px (PC wide). Library tabs
  become rail items from landscape upwards; the in-pane tab pills hide.

**Now Playing**
- Cover art is the flexible element (`flex:1 1 auto; min-height:0;
  aspect-ratio:1; max-width:100%`), so it shrinks first and the chip row is
  never pushed off-screen.
- Chip row is one non-wrapping line with `overflow-x:auto` as the last-resort
  fallback.
- Lyrics shrink the art rather than extending the page; the deck is the only
  scroller in every mode.
- Mini player hidden on the route modes, kept on the PC tiers (§1.4).

**Landscape mobile**
- Art | controls two-column, lyrics slide in as a third column.
- Bottom sheets become **side sheets** docked to the right edge — queue, sort
  drawer, sleep timer and tag editor alike. A 90%-height bottom sheet on a
  360px-tall screen is unusable.
- `--safe-l` gutter on the rail and overlay bodies for the display cutout.

**Tablet**
- Now Playing puts the cover art **on top** with title, seek, transport and
  chips centred beneath it — *not* the landscape two-column split, which
  shrinks the art and pushes the controls into a side gutter on a
  portrait-shaped panel. This supersedes the "two-column treatment" line in
  §3.4.
- The space freed by hiding the mini player carries an **inline tag editor**
  for the playing track (tablet only); toggling lyrics swaps the deck into
  that same slot so the two never compete.
- Elsewhere: 210px labelled rail, two-column lists, four-column album grid,
  sheets become centred dialogs.

**PC (both variants)**
- Now Playing stops being a route: permanent right-hand pane, with the
  transport bar spanning the full window including the rail (the rail
  reserves bottom padding for it).
- Queue docks into the same column. At PC compact it replaces the Now Playing
  pane; at PC wide both are visible at once (rail · library · NP + queue).
- Journal, About and the Tag editor become centred card modals over the
  shell. Journal body goes two-column at PC compact, three at PC wide.
- Prose capped at `68ch`. Hover and `:focus-visible` states appear only at
  the PC tiers.

**Known gaps (deliberate, not oversights)**
- Album / artist / genre *detail* screens reuse the songs-list layout and are
  not mocked separately.
- The multi-select action bar (`#cab`), onboarding tip and toast are not
  mocked; they follow `--shell-w` and need no independent decision.
- Motion is out of scope — see the `poet-sheet-up` note in §3.2.

---

## 3. ◐ 🔴 Responsive shell — implementation (after §2 approval)

Evidence: the attached landscape screenshot shows the library at 2400×1080
with the entire UI squeezed into a 480px column floating in the middle of the
screen, the media tray overlapping the song list, and a black band along the
left edge where the display cutout is.

### 3.1 ◐ Display cutout black band (native side)

**Work items:**
- The Activity never opts into drawing behind the cutout, so in landscape the
  window is letterboxed away from it — that is the black bar in the
  screenshot, and it is not fixable from CSS.
- Set `android:windowLayoutInDisplayCutoutMode` to `shortEdges` (or
  `always`) in `res/values/themes.xml`, or set
  `window.attributes.layoutInDisplayCutoutMode` in `MainActivity`
  (`MainActivity.kt:123` area, alongside the existing status-bar theming at
  `MainActivity.kt:420-430`).
- Pair it with real edge-to-edge: the WebView must extend under the system
  bars while CSS `env(safe-area-inset-*)` keeps content clear. The viewport
  meta already carries `viewport-fit=cover` (`Shell.kt:34`), so only the
  native half is missing.
- Add horizontal `env(safe-area-inset-left/right)` padding to `#app` — today
  only bottom/top insets are used (`poet.css:257,546`), which is exactly why
  landscape cutouts have no handling.
- Test on a device with a cutout in both rotations plus gesture and
  3-button navigation.

### 3.2 ✅ Break the 480px cap

**Work items:**
- `#app { max-width:480px }` (`poet.css:80`) is applied unconditionally.
  Make it the *portrait mobile* value and let wider/landscape modes use the
  full width per the mockup breakpoints.
- The same 480px cap is duplicated on `#tray` (`poet.css:546`), `#cab`
  (`:133`), `.drawer` (`:150`), `.sheet` (`:370`), `.queue-panel` (`:561`)
  and `.editor-sheet` (`:445`). Introduce a single `--shell-w` custom
  property (or a shared class) so one breakpoint changes them together —
  otherwise the modes will drift.
- Fixed-position elements centre with `left:50%; transform:translateX(-50%)`;
  re-check each one after the width changes, especially the sheet-up
  animation (`poet.css` `@keyframes poet-sheet-up`) which bakes the
  `-50%` translate into the keyframes.

### 3.3 ✅ Breakpoints and mode detection

**Work items:**
- Define the breakpoint set once, at the top of `poet.css`, with a comment
  mapping each to a mockup mode. These are the ones the mockup implements —
  copy them, do not re-derive:
  - portrait mobile — base tier, no query
  - landscape mobile — `(max-height: 500px)`
  - tablet — `(min-width: 700px) and (min-height: 501px)`
  - PC compact — `(min-width: 1100px) and (min-height: 501px)`
  - PC wide — `(min-width: 1400px) and (min-height: 501px)`
- The `min-height` guards are load-bearing, not decoration: an 800×360
  landscape phone and an 834px tablet are indistinguishable on width alone,
  and a width-only set gives the phone the tablet layout.
- The tiers accumulate mobile-first — tablet rules still apply at PC sizes, so
  every PC-specific override must be explicit. The mockup already carries the
  counter-rules (`.np-art` width, `.np-panel`, the tray) that this ordering
  makes necessary.
- Prefer CSS-only switching. Only add a JS mode flag (e.g. a `data-mode` on
  `<html>` via `matchMedia`) where markup genuinely has to differ —
  the modal-vs-full-screen Journal in §2.4 is the likely case — and update it
  on `resize`/`orientationchange`. `AndroidManifest.xml:34` already keeps the
  Activity alive across rotation, so the WebView is not reloaded and any JS
  mode state must react rather than initialise once.
- Audit hard-coded viewport maths that assume portrait:
  `poet.js:58` (`window.innerHeight - 78` for menu flipping),
  `.lyrics-deck` `38vh`, `.art-viewer-img` `60vh` (`poet.css:414`),
  `.editor-sheet` `92vh`, `.sheet-tall` `88vh`, `.drawer` `90vh`.

### 3.4 ◐ Tablet layout

**Work items:**
- Library: two-column song list, four-column album grid and the 210px
  labelled rail instead of a centred narrow column.
- Now Playing: cover art **on top**, controls centred beneath it — see §2.6.
  (This replaces the earlier "landscape two-column treatment" plan, which the
  mockup showed to be wrong on a portrait-shaped panel.)
- Inline tag editor in the space the hidden mini player frees, swapping out
  for the lyrics deck when lyrics are on. Tablet only.
- Sheets and drawers become centred dialogs above a threshold width, per the
  mockup.
- Verify with the emulator's tablet profiles plus a foldable profile
  (unfolding is a live configuration change — see §3.3 on reacting to it).

---

## 4. ◐ 🟡 Desktop port groundwork

Not a port, just the parts that would otherwise have to be redone.

**Work items:**
- Keep every layout decision in CSS and in `Shell.kt`'s single page shell —
  no layout logic inside the per-screen `Views*.kt` builders, so the same
  server-rendered HTML can serve all modes.
- Introduce the nav-rail markup behind the PC breakpoints in `Shell.kt` and
  let CSS decide whether it renders as a rail or as today's header pills.
- Add hover states and focus-visible outlines for pointer/keyboard use —
  currently only `:active` states exist (mobile-first), and the WebView shell
  disables text selection globally (`poet.css:60-62`), which will need
  revisiting on desktop.
- Keyboard shortcuts (space = play/pause, arrows = seek/skip) — cheap to add
  once focus handling exists, and useful on tablets with keyboards.

---

## 5. ✅ 🟡 Linux desktop build (`.deb`)

**Status: done — Poet is a native Linux application.** One GTK 3 window with a
WebKitGTK view, the full UI, and GStreamer playback of local files. The
build-out, and the answers to the questions §5.4 left open, are in
**`docs/desktop-app-plan.md`**. Scoped to Debian/Ubuntu only as requested;
Windows and macOS are explicitly out of scope.

The PC layout modes (§2.4, §2.6) are pure CSS and ship inside the Android
APK regardless — a wide window in any browser already renders them. This
section is only about producing a *native Linux package*.

**What runs today** (`./gradlew :desktop:run`, `./gradlew :desktop:packageDeb`):

- `:core` — new Kotlin/JVM module holding the platform-independent code. It has
  no Android on its compile classpath, so an accidental `android.*` import
  fails the build rather than quietly re-coupling the port to the phone.
  Currently: `Models`, `MimeTypes`, `Html`, `Markdown`, `EqPresets`.
- `:desktop` — Kotlin/JVM application. Ktor CIO on `127.0.0.1` with an
  OS-assigned port (a desktop is multi-user; the phone's fixed 8080 collides),
  the frontend served straight out of `app/src/main/assets` so `poet.css` and
  `poet.js` can never drift between the two builds, a `sqlite-jdbc` store on
  the Android schema at `$XDG_DATA_HOME/poet-music/library.db`, and a
  `java.nio` folder scanner.
- Packaging — `:desktop:packageDeb` runs `jpackage --type deb` over a bundled
  runtime, so the installed app has no Java dependency. Ships a `.desktop`
  entry, a 128px icon, and declares the GStreamer runtime dependencies.
- Verified: 249 tracks scanned from a real `~/Music`, assets served, socket
  confirmed listening on loopback only, package inspected with `lintian`.

**Toolchain installed on this machine:** `jpackage` (JDK 17), `dpkg-deb`,
`fakeroot`, `binutils`, `lintian`.

**Now ported:** everything. `MusicDatabase` and `PlayerController` moved behind
`LibraryStore` and `PlayerPort` in `:core`, which unblocked moving the whole
view layer and the entire routing table there too — so both builds render from
one implementation and cannot drift. The desktop adds `DesktopLibrary` (JDBC),
`GstPlayer`, `GstEq`, `DesktopScanner`, `DesktopTags` (JAudiotagger) and
`DesktopHost`, plus the GTK window and its file chooser.

**On `lintian`:** the remaining findings are inherent to `jpackage` vendor
packages — `dir-or-file-in-opt`, no changelog/copyright, unstripped JDK
binaries. They block acceptance into the official Debian archive, not direct
distribution. Only fix them if Poet is ever submitted upstream.

### 5.1 What is already portable

The architecture is unusually kind to this port. Roughly two thirds of the
app is plain JVM Kotlin with no Android dependency:

- `server/` — Ktor CIO routes and the entire view layer are string builders
  over `MusicDatabase` / `PlayerController`. Ktor CIO runs on the JVM
  unchanged.
- `data/Models.kt`, `data/LrcParser.kt`, `data/LibrarySort.kt`,
  `server/Markdown.kt` — pure Kotlin.
- `assets/web/*` — the frontend is already a browser app.

### 5.2 What has to be replaced

| Android dependency | Desktop replacement |
| --- | --- |
| `WebView` (`MainActivity.kt:181`) | JavaFX `WebView`, JCEF, or simply open the default browser at `127.0.0.1:8080` |
| Media3 / ExoPlayer (`playback/`) | VLCJ (libvlc), GStreamer, or JavaFX `MediaPlayer` |
| `MediaSessionService` + notification | MPRIS over D-Bus, for desktop media keys and the GNOME/KDE applets |
| `android.database.sqlite` (`data/MusicDatabase.kt`) | `sqlite-jdbc`, same schema and SQL |
| SAF tree URIs (`data/LibraryScanner.kt`, `LibraryWatcher.kt`) | plain `java.io.File` / `java.nio` paths + `WatchService` |
| `android.media.audiofx` (`playback/AudioFx.kt`, `EqPresets.kt`) | libvlc / GStreamer equalizer filters |
| `MediaStore` art extraction, `TagEditor` | JAudiotagger (JVM), replacing the hand-rolled ID3 writer |

### 5.3 Work items

- **Split the modules first.** Extract `:core` (JVM-only: views, models, SQL,
  parsers) out of `:app`, leaving `:app` as the Android shell. Do this
  *before* §3's CSS work lands or the two refactors will collide in the same
  files.
- Define the platform seams as interfaces in `:core` — `PlayerPort`,
  `LibraryStorePort`, `FilePickerPort`, `ArtPort` — and move the Android
  implementations into `:app`.
- Add `:desktop`, a JVM application module implementing those ports. Start
  with the browser-shell variant (no embedded WebView) so playback and
  library work can be proven before a UI toolkit is chosen.
- Bind the Ktor server to a random free port on desktop rather than a fixed
  `8080`, and keep the `127.0.0.1`-only bind — it is a load-bearing security
  property (README, About screen), and a fixed port collides on a
  multi-user machine.
- **Packaging:** `jpackage` (bundled with JDK 17, already the project's
  toolchain) with `--type deb`. `dpkg-deb` and `fakeroot` are present on this
  machine, so no extra tooling is needed. Wire it as a Gradle task
  (`:desktop:packageDeb`) that consumes a `jlink`ed runtime image, so the
  `.deb` carries its own JRE and the package has no Java dependency.
- Ship the desktop-specific bits the package needs: `.desktop` entry, icon
  set under `/usr/share/icons/hicolor/`, MIME associations for audio types,
  and a `postinst` that refreshes the desktop database.
- Declare the real runtime dependencies in the control file (`libvlc` or
  `gstreamer1.0-plugins-*`, depending on the playback choice above).
- Verify with `lintian` before shipping, and install-test on a clean
  container rather than the build machine.

### 5.4 ✅ Decisions, settled

All three are answered, with the evidence, in `docs/desktop-app-plan.md` §1.

- **Shell** — GTK 3 + WebKitGTK bound through JNA. No toolkit is bundled: both
  libraries are stock on a Debian/Ubuntu desktop, so the `.deb` stays small and
  gets a current WebKit (2.48 on the build machine) rather than the older one
  JavaFX ships. Falls back to the default browser when they are absent.
- **Playback engine** — GStreamer `playbin` via `gst1-java-core`, with
  `equalizer-10bands` and `pitch` in the audio filter. VLCJ would have been a
  second media stack on a machine that already has GStreamer.
- **Distribution** — bare `.deb`, as requested.

---

## 6. 🟢 Follow-ups surfaced by this work

- Chip row on Now Playing has grown to five items (speed, sleep, lyrics,
  favourite, queue). If §1.2 has to scroll it even on mid-size phones,
  consider promoting favourite to the transport row and moving speed into
  the sleep-style drawer.
- The full-screen art viewer (`ViewsNowPlaying.kt:146-158`) is sized in `vw`
  and `vh` and will need landscape treatment alongside §3.3.
- The Journal's `min-height:100vh` card (`poet.css`, `.journal-screen`)
  assumes a portrait full-screen takeover; §2.4 turns it into a modal on PC,
  so the sticky bar and safe-area padding both need a mode-aware pass.
