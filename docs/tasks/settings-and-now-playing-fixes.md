# Settings & Now Playing Fixes — Task List

Three issues to investigate and fix. Each section includes the files involved,
step-by-step tasks, open questions, and complexity estimates.

---

## ISSUE 1 — Equalizer sliders interactive while EQ is disabled

**Problem:** When the "Enable equalizer" toggle is off, the frequency band
sliders (60/230/910/3.6k/14k), Bass boost, and Virtualizer sliders remain
visible and draggable — moving them has no audible effect and is confusing.

### Files involved

| File | What it does |
|------|-------------|
| `core/.../server/ViewsSettings.kt:121-175` | `equalizerCard()` — renders the full EQ card HTML including toggle, presets, bands, and strength sliders |
| `core/.../server/ViewsSettings.kt:145-161` | Band slider rendering — adds `eq-off` CSS class when disabled but still emits all `<input>` elements |
| `core/.../server/ViewsSettings.kt:163-166` | Bass/virtualizer rendering — same pattern, `eq-off` class for dimming |
| `core/.../server/PoetRoutes.kt:683-714` | Route handlers for `/api/eq/*` — every route re-renders the full card via `SettingsViews.equalizerCard(deps.eq)` |
| `core/.../playback/EqPort.kt:7-18` | `EqState` data class — holds `enabled`, `bands`, `bassStrength`, `virtualizerStrength` |
| `core/.../playback/EqPort.kt:29-35` | `EqPort` interface — `setEnabled()`, `setBand()`, `setBassStrength()`, `setVirtualizerStrength()` |
| `app/.../playback/AudioFx.kt:151-194` | Android EQ impl — persists `eq_on` setting, applies to ExoPlayer audio effects |
| `desktop/.../GstEq.kt:41,106-109` | Desktop EQ impl — persists `eq_on` setting, pushes to GStreamer element |
| `app/src/main/assets/web/poet.css:495-497` | `.eq-strength { margin-bottom:14px }` and `.eq-off { opacity:0.45 }` — visual dimming only |
| `app/src/main/assets/web/poet.js:633-649` | `poetEqLabel()` and `poetEqStrength()` — live label update during drag |

### Current flow

1. `equalizerCard(eq)` always emits all slider markup, gated only by
   `s.available` / `s.bassAvailable` / `s.virtualizerAvailable` — never by
   `s.enabled`.
2. When disabled, the bands container gets class `eq-off` (opacity 0.45) and
   each strength row gets `eq-off`. Sliders remain fully interactive.
3. The toggle button posts to `/api/eq/enabled?on=0|1`, which calls
   `deps.eq.setEnabled(on)` then responds with a fresh `equalizerCard(eq)`.
4. The response swaps into `#eq-card` — so the card IS re-rendered on toggle.

### Design decision: server-side vs client-side hiding

**Recommended: server-side.** The toggle already triggers a full card re-render
via htmx. When `s.enabled` is false, `equalizerCard()` can simply skip emitting
the bands, bass, virtualizer, and preset markup. This is the simplest approach:
no new JS, no CSS tricks, no risk of stale state. The tradeoff (a round-trip on
toggle) is already accepted by the current design.

### Step-by-step tasks

1. In `ViewsSettings.kt` `equalizerCard()` (line 121), after reading
   `val s = eq.state()`, wrap the **bands** (lines 145-161, 172),
   **bass** (lines 163-164, 173), and **virtualizer** (lines 165-166, 174)
   in an `if (s.enabled) { ... }` block. Keep the preset row visible and
   functional when disabled. When disabled, emit the toggle and presets,
   but omit the sliders.

2. The `eq-off` CSS class is no longer emitted — the disabled controls
   are omitted entirely after re-render. No CSS changes required.

3. Verify: toggle OFF → sliders disappear; toggle ON → sliders reappear with
   their persisted values. Drag a slider while enabled → value persists across
   toggle off/on cycles.

4. No route changes needed — the existing `/api/eq/enabled` handler already
   re-renders the card with fresh state.

### Open questions (resolved)

- **Presets:** Kept visible and functional when disabled — user confirmed.
- **Strength sliders (bass/virtualizer):** Hidden when disabled, same as
  frequency bands — user confirmed.

### Complexity: **Low**

The toggle already triggers a full re-render. The change is purely conditional
markup emission in one function. No new routes, no JS, no CSS.

---

## ISSUE 2 — Redundant "← Library" text button on Now Playing screen

**Problem:** A "← Library" text link sits below the header on Now Playing,
above the album art. It is visually redundant with the system back button and
the Library tab in the header.

### Files involved

| File | What it does |
|------|-------------|
| `core/.../server/ViewsNowPlaying.kt:81` | `<button class="backlink" hx-get="/screens/library" hx-target="#main-container">← Library</button>` — the element to remove (normal state) |
| `core/.../server/ViewsNowPlaying.kt:65` | Same element in the "Nothing playing" empty state |
| `core/.../server/PoetRoutes.kt:108-114` | `GET /screens/now-playing` — serves the Now Playing screen (no changes needed) |
| `app/src/main/assets/web/poet.js:856-863` | `poetBack()` — hardware back handler, independently navigates to `/screens/library` |
| `app/src/main/assets/web/poet.css:154` | `.backlink` base style |
| `app/src/main/assets/web/poet.css:784` | `.np-screen .backlink { display:none }` — already hidden in landscape (`max-height: 500px`) |

### Current flow

- The button uses `hx-get="/screens/library" hx-target="#main-container"` to
  swap in the library view. No JS references it. No ID, no event listeners.
- `poetBack()` in poet.js independently handles hardware back by navigating to
  `/screens/library` — it does NOT reference this button.
- There is no `hx-boost`, no `pushState`, no browser history integration
  anywhere in the app. Navigation state is tracked only in the `poetScreenUrl`
  JS variable.
- The button is already hidden in landscape mode via CSS
  (`@media (max-height: 500px)`), confirming it was always intended as a
  mobile-portrait affordance.

### Risk assessment

**⚠️ Not fully redundant on all platforms.** The investigation found that on
touch devices without a hardware back button (tablets, some phones using
gesture nav), this button is the **only visible way** to leave Now Playing.
The `poetBack()` function is documented as being called by Android (`/*
back-button support: Android calls poetBack() */`), meaning on desktop or
non-Android platforms the button is the sole escape.

**Recommendation:** Remove the button but confirm the user is aware of this
tradeoff. If the user proceeds, the fix is straightforward.

### Step-by-step tasks

1. In `ViewsNowPlaying.kt`, remove line 81 (the `<button class="backlink">`
   in the normal Now Playing state). Also remove the surrounding whitespace
   if it creates a gap.

2. In `ViewsNowPlaying.kt`, remove line 65 (the same button in the "Nothing
   playing" empty state). The empty state div at line 64 already has
   `text-align:center` so the left-floated button was visually orphaned anyway.

3. Optionally remove the now-unused `.backlink` CSS rule at `poet.css:154` if
   no other screen uses it. **Check first:** grep for `class="backlink"` across
   all `.kt` files. If other screens (e.g. Settings, About, Artist detail)
   still use it, keep the CSS.

4. No route changes needed — `GET /screens/now-playing` still serves the
   screen, just without the backlink element.

### Open questions

- **User confirmation needed:** The user said this is redundant, but the
  investigation shows it's the only visible escape on touch devices without
  hardware back. Confirm the user wants to remove it entirely, or whether
  they'd prefer to keep it only on specific breakpoints (e.g. hide on tablet
  where nav rail exists, keep on phone portrait).
- **Desktop (Linux):** The WebKitGTK window has no system back button. If the
  user is on Linux, removing this leaves no visible escape from Now Playing.
  The Escape key calls `poetBack()` which navigates to library, but that's
  not discoverable.

### Complexity: **Low**

Remove 2 lines of HTML, optionally clean up CSS. No behavioral changes.

---

## ISSUE 3 — Queue drawer doesn't support swipe-down-to-close

**Problem:** The Queue drawer (slide-up panel with Now Playing card + Next up
list) can only be closed via the chevron button, "Clear", or hardware back.
Swiping down on it does nothing — violating standard bottom-sheet UX.

### Files involved

| File | What it does |
|------|-------------|
| `core/.../server/ViewsQueue.kt:19-33` | `queuePanel()` — renders `<div class="queue-shield">` + `<div class="queue-panel">` |
| `core/.../server/ViewsQueue.kt:35-98` | `queuePanelBody()` — inner content swapped on queue mutations |
| `app/src/main/assets/web/poet.css:648-684` | Queue panel CSS — `.queue-panel` with `transform:translateX(-50%)`, `animation:queue-up`, `max-height:86vh` |
| `app/src/main/assets/web/poet.js:30-35` | `openQueue()` / `closeQueue()` — htmx fetch + innerHTML clear |
| `app/src/main/assets/web/poet.js:392-429` | **Existing swipe-to-close IIFE** for `#sheet-root .drawer, #sheet-root .sheet` — the reference pattern |
| `app/src/main/assets/web/poet.js:431-464` | Queue drag-to-reorder IIFE — handles `.q-grab` touch events, unrelated to close gesture |
| `app/src/main/assets/web/poet.js:174-176` | `poetBottomSheet()` — guard function, returns true only in base tier (portrait phone) |

### Current architecture

- The queue panel lives in `#queue-root`, not `#sheet-root`. This is why the
  existing swipe-to-close IIFE (which targets `#sheet-root`) doesn't cover it.
- The queue panel uses the same CSS transform pattern as sheet-root drawers:
  `transform:translateX(-50%)` for centering, `animation:queue-up` for slide-up.
- The queue panel has `max-height:86vh` and `overflow-y:auto` on `#qp-body`,
  so it scrolls internally.
- The queue header (`.qp-hdr`) contains the back button, title, and clear
  button — no dedicated drag handle (`.sheet-grab`) element.
- The existing swipe-to-close IIFE uses `panel.scrollTop <= 0` to only initiate
  dragging when the panel is scrolled to the top — this is important for the
  queue panel too since `#qp-body` scrolls.

### The reference pattern (poet.js:392-429)

```javascript
// Existing IIFE for #sheet-root drawers:
// touchstart: find panel via e.target.closest('#sheet-root .drawer, #sheet-root .sheet')
// touchmove:  if dy > 8 && scrollTop <= 0 → start dragging, apply translate(-50%, dy)
// touchend:   if dy > 90 → closeSheet(), else spring back
// Guard: poetBottomSheet() — only in base tier (portrait phone)
```

This pattern is clean, minimal, and already handles the edge cases (scroll
position, input elements, landscape guard). It should be extracted/reused
rather than reinvented.

### Step-by-step tasks

1. **Add a drag handle to the queue panel.** In `ViewsQueue.kt`, add a
   `<div class="sheet-grab"></div>` element at the top of `.queue-panel`,
   before `.qp-hdr`. This gives users a visual affordance for the swipe gesture
   and matches the existing drawer convention. The CSS for `.sheet-grab`
   (`poet.css:463`) already exists and is hidden in landscape/tablet via
   `@media` rules (`poet.css:810,897`).

2. **Extract the swipe-to-close logic into a reusable function** in `poet.js`.
   Replace the existing IIFE (lines 396-429) with a function like:
   ```javascript
   function poetSwipeClose(selector, closeFn) { ... }
   ```
   that takes a CSS selector and a close callback. The existing call becomes:
   ```javascript
   poetSwipeClose('#sheet-root .drawer, #sheet-root .sheet', closeSheet);
   ```

3. **Add a second invocation** for the queue panel:
   ```javascript
   poetSwipeClose('#queue-root .queue-panel', closeQueue, '#qp-body');
   ```
   This reuses the exact same gesture logic — same threshold (90px), same
   deadzone (8px), same `scrollTop <= 0` guard, same `poetBottomSheet()`
   landscape guard, same `translate(-50%, dy)` transform.

4. **Handle the queue panel's scroll context.** The `#qp-body` element has
   `overflow-y:auto`, so `panel.scrollTop` must be checked on the scrollable
   child, not the panel itself. The third parameter (`scrollSel`) resolves
   this: `poetSwipeClose` queries `panel.querySelector(scrollSel)` to find
   the scroll container. A `startedInScroll` flag tracks whether the touch
   began inside the scroll container — header/handle touches can always
   initiate dismissal regardless of scroll position, while content touches
   require `scrollTop <= 0`.

5. **Verify on both platforms:**
   - Android WebView: swipe down on queue panel → drags with finger → past
     threshold → closes; below threshold → springs back; scrolling inside
     queue body → no accidental close trigger.
   - Linux WebKitGTK: same behavior (WebKitGTK supports touch events).
   - Landscape/tablet: swipe gesture disabled (poetBottomSheet() returns
     false), drawers become side-docked.

6. **Optional: apply the same pattern to the tag editor** (`#modal-root
   .editor-sheet`). The tag editor is a full-height sheet that could benefit
   from swipe-to-close. This is a separate task but the extracted function
   makes it trivial to add later.

### Open questions

- **Drag handle placement:** The `.sheet-grab` element is a small rounded bar
  (40px × 4px, `var(--grabber)` color). Adding it to the queue panel header
  area (above `.qp-hdr`) is the consistent choice. Alternatively, the entire
  `.qp-hdr` could be the drag target — but that conflicts with the back button
  and clear button tap targets.
- **Tag editor swipe-to-close:** The task description mentions the tag editor
  as a candidate. If desired, add `poetSwipeClose('#modal-root .editor-sheet',
  poetCloseEditor)` — same pattern, same file. Recommend doing this in the
  same PR since the function is being extracted anyway.
- **Conflict with drag-to-reorder:** The existing queue drag-to-reorder
  (poet.js:431-464) targets `.q-grab` handles inside `#qp-body`. The new
  swipe-to-close targets the panel itself (`.queue-panel`). These are
  different selectors and different touch targets — no conflict. The
  `.q-grab` elements have `touch-action:none` which prevents the browser from
  scrolling, and the swipe-to-close only initiates when `scrollTop <= 0` and
  `dy > 8` — both are fine.

### Complexity: **Medium**

The gesture logic already exists and just needs to be extracted + applied to a
new selector. The main complexity is ensuring the scroll-container check works
correctly for the queue panel's nested `#qp-body` overflow, and testing on
both platforms.

---

## Summary

| Issue | Complexity | Key risk |
|-------|-----------|----------|
| 1. EQ sliders hidden when disabled | Low | None — server-side conditional emission |
| 2. Remove "← Library" backlink | Low | **Navigation affordance on touch devices** — confirm with user |
| 3. Queue swipe-to-close | Medium | Scroll-container detection for `#qp-body` overflow |
