# Poet Music — Blur & Glass Effect Removal Analysis

**Purpose:** Document every backdrop-filter blur and glass-surface variable in the frontend so they can be safely removed. Written for the BigPickle AI review process.

---

## 1. All `backdrop-filter` Instances in `poet.css`

| Selector | Blur Radius | Line | Dark Mode Override? |
|---|---|---|---|
| `.center-shield` | `blur(4px)` | 185 | ✅ lines 43–46 |
| `.menu` | `blur(12px)` | 197 | ✅ lines 43–46 |
| `.lyrics-deck` | `blur(8px)` | 232 | ✅ lines 43–46 |
| `.sheet-shield` | `blur(2px)` | 247 | ✅ lines 43–46 |
| `#tray` | `blur(16px)` | 386 | ✅ lines 43–46 |
| `.queue-shield` | `blur(2px)` | 400 | ✅ lines 43–46 |
| `#modal-root .modal-shield` | `blur(2px)` | 436 | ✅ lines 43–46 |

**Total: 7 blur effects.** All use `backdrop-filter` + `-webkit-backdrop-filter` (Safari/WebView prefix). All are full-screen fixed overlays except `.menu` (absolute) and `.lyrics-deck` (relative, inside now-playing).

---

## 2. Glass Variables in `Shell.kt` (Light Mode Only)

| Variable | Light Value (`Shell.kt:42–44`) | Consumed By |
|---|---|---|
| `--card-glass` | `rgba(255,255,255,0.85)` | `#tray` |
| `--menu-glass` | `rgba(255,255,255,0.92)` | `.menu` |
| `--lyrics-glass` | `rgba(255,255,255,0.7)` | `.lyrics-deck` |

**Dark mode values** (poet.css lines 20–22): all three become opaque (`#1e1e24`, `#26262e`, `#1e1e24` respectively). This is because the dark-mode override block (lines 43–46) disables all blurs, so translucent glass would show smeared content behind it.

---

## 3. Consumer → Blur Mapping

| Consumer | Glass Var | Blur (Light) | Dark Opaque? |
|---|---|---|---|
| `#tray` | `--card-glass` | 16px | ✅ |
| `.menu` | `--menu-glass` | 12px | ✅ |
| `.lyrics-deck` | `--lyrics-glass` | 8px | ✅ |

The four shields use an inline dim + blur — no glass var: `.sheet-shield`,
`.queue-shield` and `#modal-root .modal-shield` are `rgba(59,54,81,0.35)`;
`.center-shield` is `rgba(59,54,81,0.4)`.

---

## 4. Dark Mode Override Block (poet.css:43–46)

```css
html[data-theme="dark"] .sheet-shield,
html[data-theme="dark"] .center-shield,
html[data-theme="dark"] .queue-shield,
html[data-theme="dark"] .menu,
html[data-theme="dark"] .lyrics-deck,
html[data-theme="dark"] #tray,
html[data-theme="dark"] #modal-root .modal-shield {
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}
```

**Covers all 7 blur selectors exactly once.** No strays.

---

## 5. Known Exceptions (Intentional No-Blur)

| Selector | Reason |
|---|---|
| `#tip-shield` (onboarding) | Android WebView compositing bug on full-screen fixed blur; explicitly documented at poet.css:378–380. |
| The 10 `--accent-shadow` consumers (§5a) | Use `box-shadow: … var(--accent-shadow)` — **not** a backdrop-filter. Before the fix in §11 this rendered as a colored glow in dark mode and was routinely mistaken for a blur leak. |

### 5a. Every `--accent-shadow` Consumer

There are **10**, not the 3 originally reported. All ten are `box-shadow`
declarations; the variable is used nowhere else, which is what makes the
theme-level fix in §11 safe.

| Selector | Line | Shadow |
|---|---|---|
| `.btn-primary` | 86 | `0 2px 8px` |
| `.sheet-btn` | 161 | `0 3px 12px` |
| `.np-art` | 217 | `0 16px 40px` |
| `.np-main` | 224 | `0 8px 20px` |
| `.ed-art-tile` | 329 | `0 8px 24px` |
| `.ed-lrc-play` | 345 | `0 3px 10px` |
| `.tray-play` | 394 | `0 3px 10px` |
| `.qp-now` | 416 | `0 4px 16px` |
| `.qp-playbtn` | 423 | `0 3px 10px` |
| `#toast.accent` | 444 | `0 8px 24px` |

---

## 6. Server-Rendered Views Check

Searched `Views*.kt` for inline `backdrop-filter` or `filter: blur`: **none found.** All styling is in `poet.css` (served as asset) or inline `:root` variables from `Shell.kt`.

---

## 7. Removal Plan

### Files to Modify

| File | Changes |
|---|---|
| `poet.css` | Remove `backdrop-filter` + `-webkit-backdrop-filter` from 7 selectors (lines 185, 197, 232, 247, 386, 400, 436). Delete dark-mode override block (lines 43–46). |
| `Shell.kt` | Update three glass variables to opaque: `--card-glass: var(--card-bg)`, `--menu-glass: var(--card-bg)`, `--lyrics-glass: var(--card-bg)`. |

### Optional Polish

- Reduce shield overlay opacity from `rgba(59,54,81,0.35)` → `~0.25` (blur no longer softens the dim).
- Verify `.menu` border `var(--card-border)` still provides sufficient contrast without blur backdrop.

---

## 8. Regression Checklist

After removal, verify in **both light and dark mode**:

1. **Tray** (`#tray`) — solid background, no transparency.
2. **Context menu** (`.menu`) — solid card background, border visible.
3. **Lyrics deck** (`.lyrics-deck`) — solid card background, text readable.
4. **All shields** (`.center-shield`, `.sheet-shield`, `.queue-shield`, `#modal-root .modal-shield`) — dim overlay visible but not translucent.
5. **No stray translucent surfaces** — any element with `background: var(--*-glass)` must now be opaque.

---

## 9. Why Remove?

1. **WebView compositing bugs** — dark mode blur causes halo bleeding outside bounds (documented in claudeAI-review.md:40–42, Bugs.md:94–102).
2. **Performance** — backdrop-filter on full-screen fixed elements triggers GPU readback on low-end Android.
3. **Consistency** — dark mode already runs opaque; light mode should match.
4. **Simplification** — eliminates 7 blur rules, 1 override block, 3 glass variables, and the light/dark glass duality.

---

## 10. AI Self-Review Prompt

> When auditing this codebase for blur/glass effects, execute the 7-step checklist in the README "AI Review Instructions" section and produce a report in this format. Verify counts match the tables above. Flag any new blur introduced after this analysis date.

---

## 11. Verification Instructions for User-Reported Dark Mode "Blur"

**User observation:** In dark mode, a blur-like glow appears behind:
- Cover art on Now Playing screen (`.np-art`)
- Main play button on Now Playing (`.np-main`)
- Minimized player play button (`.tray-play`)

**To verify this is `box-shadow` not `backdrop-filter`:**

1. **Inspect the CSS rules** for these three selectors in `poet.css`:
   - `.np-art` (line 217): `box-shadow: 0 16px 40px var(--accent-shadow);`
   - `.np-main` (line 224): `box-shadow: 0 8px 20px var(--accent-shadow);`
   - `.tray-play` (line 394): `box-shadow: 0 3px 10px var(--accent-shadow);`

2. **Confirm `--accent-shadow` definition** in `Shell.kt:34`:
   ```kotlin
   --accent-shadow: ${accent}80;
   ```
   This appends `80` (50% opacity hex) to the accent color (e.g., `#b9a5ec80`).

3. **Confirm dark-mode override** at `poet.css:43–46` explicitly disables `backdrop-filter` on all 7 blur elements. None of `.np-art`, `.np-main`, `.tray-play` are in that list.

4. **Test in browser DevTools** (or WebView remote debugging):
   - Toggle `box-shadow` off on each element → glow disappears
   - Toggle `backdrop-filter` off → no visual change (already `none` via override)
   - Computed style shows `backdrop-filter: none` on all three in dark mode

5. **Conclusion to report:** The glow is a themed `box-shadow` using the accent color at 50% opacity. It is **not** a `backdrop-filter` leak. The dark-mode override correctly disables all actual blurs. If the glow is undesirable, the fix is to make `--accent-shadow` theme-aware — not to remove `backdrop-filter` again.

### 11a. Resolution (verified and shipped)

The diagnosis above was confirmed against the code: the dark block
(`poet.css:10–36`) rethemes ~20 variables but never touched `--accent-shadow`,
so a 50%-alpha accent was painted at up to a 40px radius over the `#16151d`
canvas — a colored halo on all 10 surfaces in §5a.

**The trap step 5 missed.** `poet.js` `setAccent()` pinned `--accent-shadow` as
an *inline* style on `<html>`. Inline styles outrank any stylesheet rule, so a
dark-mode variable override alone works on page load and then silently
regresses the first time the accent is changed in Settings, until reload. This
is the same hazard the codebase already documents for `--bg`
(`poet.css:8–9`, `poet.js` `setTheme`).

**What was done:**

| File | Change |
|---|---|
| `poet.css` | Added `--accent-shadow: rgba(0,0,0,0.5)` to the `html[data-theme="dark"]` block, beside `--shadow-card: rgba(0,0,0,0.45)`. One line, covers all 10 consumers, preserves each element's own shadow geometry. |
| `poet.js` | `setAccent()` no longer pins the accent-tinted value while dark; `setDark()` drops it entering dark and restores it from `window.POET.accent` leaving dark — mirroring the existing `--bg` handling. |
| `Shell.kt` | Unchanged. `:root` (0,1,0) is outranked by `html[data-theme="dark"]` (0,1,1), and the inline `<style>` already precedes the stylesheet link. |

No `backdrop-filter` rule was touched, and light mode is unchanged — every one
of the 10 surfaces keeps its accent-tinted shadow at full strength there. The
light-mode blur removal in §7 was **not** carried out and remains a proposal.