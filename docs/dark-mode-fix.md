# Poet Music — Dark Mode Visual Fixes

Several controls on the Settings screen are effectively invisible in dark mode:
they render as white lozenges with near-white labels. The "Rescan when older
than", Equalizer "Preset", "Playback" and "Canvas tint" pill groups are all
affected. **They are one bug, not four.**

All four use `.tint-pill` (`poet.css:352`), which declares no `background`. The
global button reset (`poet.css:64`) sets only `font-family` and `color`, so the
WebView falls back to the UA default `ButtonFace` — an opaque near-white. No
`color-scheme` is declared anywhere in the project, so that default never flips
for dark mode. Meanwhile `--ink` becomes `#f0f0f5` under `html[data-theme="dark"]`
(`poet.css:12`). Result: `#f0f0f5` text on a white button, roughly 1.05:1 contrast.

Legend: 🔴 high priority · 🟡 medium priority · 🟢 nice-to-have

**Status: all five items landed.** Each section keeps its diagnosis and records
what was actually done. The regression steps are folded into `docs/Bugs.md`
(12.1, 12.2) so they run with the rest of the UI checklist.

---

## 1. 🔴 Selectable pills are white-on-white

**Symptom:** in dark mode the labels `6h` / `12h` / `24h`, `Flat` / `Rock` / `Pop`
/ `Jazz` / `Classical` / `Dance` / `Custom`, and `Always previous` / `3 seconds` /
`5 seconds` / `10 seconds` are unreadable. The selected pill is also
indistinguishable, because `.tint-pill.on` (`poet.css:353`) only swaps the border
to `var(--ink)` — itself near-white in dark mode, over a white button.

**Root cause:** `.tint-pill` has no background of its own (see intro). It was
reused for three controls that are not canvas tints and so never get the inline
`style="background:…"` that makes the class work in the one place it belongs.

**The design system already has the right component.** `.chip`
(`poet.css:235-237`) is the theme-aware selectable pill, used by Now Playing
(`ViewsNowPlaying.kt:114-118`):

```css
.chip    { background:var(--overlay-neutral); color:var(--ink); ... }
.chip.on { background: var(--accent-soft); }
```

Both tokens are defined in the light `:root` block *and* the dark override block,
so `.chip` is legible and its selected state is visible in either theme.

**Work items:** ✅ done
- [x] Repoint the EQ preset buttons (`ViewsSettings.kt`) from `tint-pill` to `chip`.
- [x] Repoint the playback restart-threshold buttons to `chip`.
- [x] Repoint the scan-interval buttons to `chip`.
- [x] Canvas-tint buttons deliberately **not** repointed — see §2.
- [x] `.chip` metrics (`9px 16px` / `99px` / 13px / 600) already matched
      `.tint-pill` exactly, so the layout did not shift. The only delta is
      `.chip`'s `border:none` vs the tint pill's 1.5px border, which is the
      intended look — it now matches the Now Playing chips.
- [x] `poet.js`'s `.tint-pill` query (which re-syncs the `on` class after a tint
      change) now touches only the tint buttons, which is what it always meant.

## 2. 🔴 Canvas-tint pill labels vanish

**Symptom:** `Lavender`, `Cream` and `Sage` are unreadable in dark mode. Unlike §1
these buttons *do* have a background — the inline light tint from
`Shell.CANVAS_TINTS` — but their text follows `--ink`, which is near-white in dark
mode.

**Root cause:** the tint pill's background is *always* a light tint by design, so
its label must not follow the theme's ink token.

**Work items:** ✅ done
- [x] `color:var(--ink)` → the fixed dark ink `#3b3651`. This is the same value
      already used for text sitting on `var(--accent)` throughout the stylesheet
      (`.btn-primary`, `.pill.active`, `.sheet-btn`, `.ed-tab.active`,
      `#toast.accent`), so it is an established convention for "ink over a
      guaranteed-light surface", not a new magic number.
- [x] Explicit fallback `background:#ffffff`, so the pill can never inherit the
      UA `ButtonFace` again. **Not** `var(--card-bg)` as first sketched — pairing
      a theme-varying background with a fixed dark label would have recreated the
      same mismatch in dark mode.
- [x] `.tint-pill.on` border `var(--ink)` → `#3b3651`, matching the label.
- [x] Same reasoning applied to the unselected border: `var(--card-border-soft)`
      is `rgba(255,255,255,0.14)` in dark mode and would have vanished against a
      light tint, so it is pinned to the light-theme `rgba(59,54,81,0.15)`.

## 3. 🟡 Declare `color-scheme` so UA chrome follows the theme

**Root cause, generalised:** the project never tells the WebView which colour
scheme is active, so every UA-rendered surface stays in light mode — button
faces, native `<input type="range">` tracks, scrollbars, and any control added in
future that is missing an explicit background.

**Work items:** ✅ done
- [x] `color-scheme: light;` added to the `:root` block in `Shell.kt`.
- [x] `color-scheme: dark;` added to `html[data-theme="dark"]` in `poet.css`.
- [x] Slider re-check: `.seek` is fully custom (`-webkit-appearance:none` plus an
      inline gradient background) so it is unaffected. `.eq-slider` uses the
      native vertical appearance with `background:transparent`, so its UA track
      now darkens with the theme; the `accent-color:var(--accent)` thumb is a
      pastel and still reads against it.

This is defence-in-depth against this whole class of bug recurring. It is **not**
a substitute for §1 and §2 — fix those explicitly rather than relying on the UA.

## 4. 🟡 Tray prev/next icons are dark-on-dark

**Symptom:** the ⏮ / ⏭ buttons in the persistent bottom tray are barely visible in
dark mode. Affects every screen, not just Settings.

**Root cause:** the SVG fills are hardcoded `fill="#3b3651"` (`Shell.kt:106` and
`Shell.kt:110`) while `#tray` is `var(--card-glass)` = `#1e1e24` in dark mode
(`poet.css:20`, `poet.css:534`) — dark navy on near-black.

**Work items:** ✅ done
- [x] Both SVGs' `fill="#3b3651"` → `fill="currentColor"`.
- [x] Verified (not assumed) that `.tray-btn` resolves to `var(--ink)`: it sets no
      `color` of its own and inherits the `button { color: var(--ink) }` reset.
      No CSS change needed.
- [x] Play/pause icons left alone. `ICON_PLAY_SM` / `ICON_PAUSE_SM` /
      `ICON_PLAY_LG` / `ICON_PAUSE_LG` and the `.cab-*` icons hardcode their fills
      correctly — they sit on `var(--accent)` and `var(--panel-strong)`, which are
      light/fixed in both themes.

## 5. 🟢 Sweep for the same failure mode elsewhere

The bug is "a control whose colour comes from the UA or from a hardcoded hex,
while its background comes from a theme token (or vice versa)". Find the rest
before they are reported.

**Work items:** ✅ done — and it found more than expected

- [x] **Interactive classes with no `background`.** Parsed every rule in
      `poet.css` and flagged the interactive ones missing a background. After §2,
      `.tint-pill` is gone from the list. Everything still on it is either a
      `<div>` (`.hdr-brand`, `.row`, `.album-card`, `.journal-*`, `.tray-info`,
      `.art-viewer-img` — no `ButtonFace` risk) or a control whose call site
      always supplies an inline background (`.swatch` gets its accent colour,
      `input.seek` its gradient). No further changes needed.

- [x] **Hardcoded `#3b3651` / `#f5f3fa` fills.** Three more instances of the §4
      bug, all fixed the same way (`currentColor`, inheriting `--ink`):

  | Icon | Sits on | Dark-mode result before |
  |---|---|---|
  | Now Playing prev/next (`ViewsNowPlaying.kt`) | `.np-side` → `var(--card-bg)` `#1e1e24` | dark navy on near-black |
  | Queue back chevron (`ViewsQueue.kt`) | `.qp-back` → `var(--overlay-neutral)` | ≈1.1:1, effectively invisible |
  | All 12 drawer action icons (`ViewsDrawer.kt`) | `.dicon` → `var(--accent-soft)` in dark | ≈1.9:1, under the 3:1 floor for non-text |

      Confirmed correct and left alone: `ICON_QP_PLAY`/`ICON_QP_PAUSE` (on
      `.qp-playbtn` = `var(--accent)`), `CHECK_SVG` in `.row-check`/`.pl-check`
      (only rendered once the box is `var(--accent)`), the "Play all" triangle
      (inside `.btn-primary`), the journal portrait pencil (on `var(--accent)`),
      the `.cab-*` icons and tip banner (on `var(--panel-strong)`), and
      `ICON_D_DELETE`/`ICON_D_TIMER_OFF` (fixed rose `#c25f6e`, legible on both).

- [x] Logged as **12.1** and **12.2** in `docs/Bugs.md`, with "do not reintroduce
      by" notes and two new entries on the pre-merge regression checklist.

**Still open:** a full dark-mode pass over Library, Tag editor, Journal and About.
The sweep above was driven by the two known failure modes (missing background,
hardcoded ink) and covers those globally, but it is not a substitute for looking
at each screen in dark mode.

---

## Verification

1. Build and install: `./gradlew installDebug`.
2. Open **Settings → Appearance → Dark**.
3. Confirm each pill group is legible **and** shows a visibly selected option:
   - Library scan → "Rescan when older than" (`6h` / `12h` / `24h`) — requires
     "Keep library in sync" to be on.
   - Equalizer → "Preset".
   - Playback → the restart thresholds.
   - Accent color → "Canvas tint" (`Lavender` / `Cream` / `Sage`).
4. Tap through each group and confirm the selected state moves and stays visible.
5. Confirm the tray ⏮ / ⏭ icons read clearly against the tray background, and the
   same for Now Playing's prev/next, the queue panel's back chevron, and the ⋯
   drawer's action icons (§5).
6. Switch back to **Light** and repeat steps 3-5 — no regression, and the canvas
   tint pills must still show their tint as the pill background.
7. Reload the app (kill and relaunch) to confirm the server-rendered markup and
   the `poet.js` client-side class sync agree on which pill is `on`.

`./gradlew compileDebugKotlin testDebugUnitTest` passes on these changes, but it
proves nothing about contrast — steps 2-7 are a manual check on a device.
