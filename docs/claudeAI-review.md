# Poet Music — Code Review (Claude)

A second-opinion review, written against `LingAI-review.md`. Every claim in
that document was checked against the source at commit `b816fd3`; this file
records what held up, what didn't, and what the first review missed. All
findings cite `file:line` so they can be re-checked.

---

## Verdict on the LingAI review

It is a good review. The architectural read is accurate, the strengths section
is fair, and 7 of its 10 findings are real. But one finding is a false
positive, one names the wrong evidence for a real problem, and the highest-
severity issue in the codebase — a genuine XSS sink — is described in a way
that points at the safe code and away from the vulnerable code.

| # | LingAI claim | Verdict |
|---|---|---|
| 1 | `MainActivity.kt` large, `onCreate` does too much | ✅ Confirmed |
| 2 | `PlayerController` latch boilerplate; `onMain()` is cleaner | ✅ Confirmed |
| 3 | `Views.kt` is a 1169-line monolith | ✅ Confirmed |
| 4 | XSS surface; `CANVAS_TINTS` / `--accent` CSS injection | ⚠️ Right conclusion, wrong evidence — see below |
| 5 | No unit tests | ✅ Confirmed |
| 6 | Hardcoded `versionCode` / `versionName` | ✅ Confirmed (and worse than stated) |
| 7 | `local.properties` is committed | ❌ **False** |
| 8 | `Shell.kt` holds 530+ lines of inline CSS | ⚠️ Confirmed, undercounted |
| 9 | `proguard-rules.pro` is empty | ✅ Confirmed (but moot for a different reason) |
| 10 | `clearCache(false)` / cookie comment worth verifying | ✅ Confirmed — the comment is wrong |

---

## Corrections

### #7 is a false positive

`local.properties` is **not** tracked. `git ls-files` does not list it,
`.gitignore:14` contains the entry, and `git check-ignore -v local.properties`
confirms the rule matches. The file exists on disk, which is presumably what
was observed, but that is correct and expected — it is a machine-local file
that is properly ignored.

This bug *did* exist once: `docs/Bugs.md` §1.1 records that a newline-less
append merged two `.gitignore` entries into `buildlocal.properties`, un-ignoring
both `build/` and `local.properties`. It was fixed in commit `f3ef832`. The
review appears to have surfaced the historical issue rather than the current
state.

### #4 names the safe code, not the vulnerable code

The review singles out `Shell`'s `CANVAS_TINTS` values being interpolated into
CSS, and calls `--accent: $accent` "particularly risky if user input ever flows
there". Neither is a sink:

- `Shell.CANVAS_TINTS` (`Shell.kt:10`) is a hardcoded
  `mapOf("Lavender" to "#f2effa", …)`. It is only ever read by map lookup with
  a literal fallback, and the setter validates membership before storing
  (`PoetServer.kt:590`, `if (name in Shell.CANVAS_TINTS)`). Nothing
  user-controlled can reach it.
- `--accent: $accent` does not come from `CANVAS_TINTS` at all. It comes from
  `db.getSetting("accent", "#b9a5ec")`, and its setter validates
  `Regex("#[0-9a-fA-F]{6}")` before writing (`PoetServer.kt:580`). A value that
  isn't six hex digits is silently discarded, so the CSS variable can only ever
  hold a colour literal.

**The real hole the review did not name** is the `ids` query parameter.
`/api/library/drawer` and `/api/library/sub` read
`call.request.queryParameters["ids"]` and pass the **raw** string to the view,
while using only the parsed-and-filtered `idList(idsRaw)` for the emptiness
guard. So `?ids=1,<payload>` passes the guard (`[1]` is non-empty) and the raw
payload reaches, unescaped:

- `Views.kt:348` — `val q = "ids=$ids&$cq"`, interpolated into `hx-get` /
  `hx-post` attributes at lines 374, 390–392, 396, 399, 404
- `Views.kt:415` — `val back = "poetSubBack('$ids','${'$'}{esc(cq)}')"`. Note
  that `cq` **is** escaped here and `ids` is not, in the same expression. `$back`
  then lands inside `onclick=` at lines 439, 459, 495, 500, 510, 515.
- `Views.kt:444` — `hx-post="/api/playlist/create?ids=$ids"`
- `Views.kt:450` — `onclick="poetAddPlaylists('$ids')"`
- `Views.kt:516` — `hx-post="/api/tracks/delete?ids=$ids"`

The `onclick` cases are the sharp ones: `ids` lands inside a **JS string
literal**, where `esc()` would not have been the right tool anyway. There is no
JS-string escaper in `Html.kt`, so the code had nothing correct to reach for.

Two smaller unescaped spots, also unmentioned:

- `Views.kt:655` — a file extension derived from the on-disk filename
  (`t.displayName.substringAfterLast('.')`) is interpolated into a `value="…"`
  attribute unescaped. A filename containing `"` breaks out of the attribute.
- `Views.kt:89,93` — `hx-post="/api/queue/replace?$cq…"` uses raw `$cq` where
  the near-identical `Views.kt:67–69` wraps the same value in `esc(cq)`. Low
  practical risk (`QueueCtx.query()` URL-encodes each component), but the
  inconsistency is exactly the kind that becomes a bug after a refactor.

To be fair to the rest of the codebase: escaping is otherwise disciplined.
Every title, artist, album, playlist and folder string checked goes through
`esc()`; the toast/`HX-Trigger` path goes through `jsonStr()`; the SVG
placeholder escapes its initials (`PoetServer.kt:646`).

### #8 undercounts, and misses the bigger half

`Shell.kt` is 1326 lines. The CSS is 513 lines (22–536), not "530+". But the
larger block is the **713 lines of JavaScript** (608–1322), which the review
doesn't mention. Extracting only the CSS would have left the majority of the
file in place.

### #9 is right, but moot for a stronger reason

`proguard-rules.pro` is 750 bytes of entirely commented-out boilerplate — zero
active directives, correct. But the reason it doesn't matter today is stronger
than "no rules": `app/build.gradle.kts` has **no `buildTypes` block at all**.
There is no `release {}`, no `isMinifyEnabled`, no `proguardFiles(...)`. The
file is not even wired into the build. Notably, the commented-out template at
lines 8–12 is *exactly* the `@JavascriptInterface` keep rule this app would
need for `MainActivity.PoetNativeBridge` (`MainActivity.kt:205`), left
uncommented.

### #10 — the comment is not just worth verifying, it's wrong

```kotlin
// Drop the WebView disk cache (album art HTTP responses); 'false'
// keeps cookies and DOM storage intact.
if (::web.isInitialized) web.clearCache(false)
```

`clearCache(boolean includeDiskFiles)` with `false` clears **only the RAM
cache and leaves the disk files** — the opposite of what the comment claims it
does. And `clearCache` has never touched cookies or DOM storage in either mode,
so the stated justification for choosing `false` is describing behaviour the
parameter doesn't control. The privacy intent in the comment requires `true`.

---

## Findings the first review missed

**A. The 1-second latch swallows its own timeout.** All three blocking bridges
in `PlayerController` (`queueItems()` :293, `advanceShuffleMode()` :436,
`advanceRepeatMode()` :531) call `latch.await(1, TimeUnit.SECONDS)` and
**discard the boolean return value**. On a slow main thread the call returns
whatever partial or stale data the local variable happens to hold, with no log
and no signal to the caller — `queueItems()` would silently serve a truncated
queue. This is a correctness bug hiding inside the boilerplate the review
flagged only for verbosity.

**B. The About screen hardcodes its own version.** Beyond `versionCode`/
`versionName` in the build file, `Views.kt:1085` renders a literal
`**Version 1.0**`. That is a second copy of the version that no release process
would ever update, so it will silently go stale the first time the app is
bumped.

**C. `targetSdk` lags `compileSdk` by two.** `compileSdk = 36` but
`targetSdk = 34`. Not a bug, but it forfeits the behavioural changes and Play
Store compliance of the newer levels while compiling against them.

**D. No CI.** No `.github/`, no pipeline of any kind. With tests now in the
repo, nothing runs them on push.

**E. `testInstrumentationRunner` is declared with no tests and no dependency.**
`build.gradle.kts:18` names `AndroidJUnitRunner`, but there was no `src/test`,
no `src/androidTest`, and no test dependency — a leftover from the project
template that reads as if a test setup exists.

---

## What this PR changes

Ordered by severity.

1. **Fixed the `ids` injection at the source.** `/api/library/drawer` and
   `/api/library/sub` now rebuild the id string from the parsed numeric list
   (`idsParam(parseIds(raw))`) instead of echoing the raw query value. This
   kills every downstream sink at once — attributes and inline JS alike — and
   is enforced by type rather than by remembering to escape at each of the
   eleven interpolation points. `parseIds` / `idsParam` live in `Html.kt`
   alongside the other escaping helpers, and are covered by `TrackIdsTest`.
2. **Escaped the two remaining spots**: the file extension in the tag editor's
   rename field, and `$cq` in `masterControls`, now consistent with `songRow`.
3. **Fixed the WebView cache call** to `clearCache(true)` so it does what the
   comment intends, and rewrote the comment to describe the API accurately.
4. **Made the blocking bridge honest.** One `onMainBlocking(fallback, block)`
   helper replaces the three hand-rolled latches; it **checks `await()`'s
   return value**, logs on timeout, and returns an explicit fallback. Same
   semantics, same 1 s deadline, ~40 fewer duplicated lines.
5. **Split `Views.kt`** (1169 lines) into seven files by screen area —
   `ViewsShared`, `ViewsLibrary`, `ViewsDrawer`, `ViewsTagEditor`,
   `ViewsNowPlaying`, `ViewsQueue`, `ViewsSettings` — following the comment
   banners that were already there. Function bodies moved verbatim.
6. **Extracted `Shell.kt`'s CSS and JS** into `assets/web/poet.css` (481 lines)
   and `assets/web/poet.js` (696 lines), served by the existing
   APK-asset interceptor (`MainActivity`) with the Ktor `/assets/{name}` route
   as fallback. `Shell.kt` drops from 1326 to 154 lines. Only the genuinely
   per-request pieces stay inline: the `:root` block carrying `$accent`/`$bg`,
   and the `window.POET` bootstrap plus the mode-indexed icon arrays. Both are
   emitted at their original document positions so cascade and script execution
   order are unchanged, and the moved content is byte-identical (verified by
   diff against the pre-split file).
7. **Added unit tests** (48, JVM-only, no Android dependencies) over the
   escaping helpers, id sanitisation, the Markdown renderer, `QueueCtx` query
   building and the rendered shell — chosen because these are the pieces that
   stand between user data and rendered markup. `ShellTest` doubles as a
   regression guard on the extraction itself: it asserts the asset links exist,
   that the inline bootstrap still precedes `poet.js`, that the bulk CSS/JS is
   no longer inline, and that `#main-container` never regains a self-load
   trigger (Bugs.md 3.1).
8. **Moved the version into the catalog.** `appVersionCode` / `appVersionName`
   in `gradle/libs.versions.toml`, referenced from `build.gradle.kts`; the
   About screen now reads `BuildConfig.VERSION_NAME` so the two can't diverge.
9. **Wrote real ProGuard rules** (JS bridge, Ktor/CIO, coroutines, mp3agic,
   Media3, widget provider) and added the missing `buildTypes { release { … } }`
   block that wires the file into the build. Minification stays **off** — the
   rules are staged for the day it's turned on, and turning it on without an
   on-device test pass would be the riskier change.

Not changed: `local.properties` (nothing to fix), `targetSdk` (a
deliberate compatibility decision, not a defect to fix in a cleanup PR), and CI
(worth its own PR).

## Regression safety

`docs/Bugs.md` warns that refactoring the shell JS, the settings screen and
playback plumbing has reintroduced bugs before, so the CSS/JS extraction moved
content **byte-for-byte** rather than rewriting it, and every item on the
Bugs.md regression checklist was re-verified after the move:

- 3.1 — `#main-container` still has no `hx-trigger="load"`; `DOMContentLoaded`
  still issues exactly one request
- 3.2 — `poetShownTrack` is still read from `#np-root[data-track-id]`, never
  forced to `-1`
- 3.3 — `hx-trigger="every 2s"` appears once, still inside the
  scan-in-progress wrapper; `#tip-shield` still has no `backdrop-filter`
- 3.4 — `setWakeMode(C.WAKE_MODE_LOCAL)` intact
- 3.5 — no `hx-confirm` / `confirm(` / `alert(` / `prompt(` in any served
  markup or asset
- 3.6 — combined startup permission request unchanged
- 10.1 — no `scrollIntoView` in app code; the deck still scrolls via
  `box.scrollTo` behind the `box._active` guard
- 11.1 — `overscroll-behavior:contain` on all five scrollable overlays,
  `touch-action:none` on all four shields, and the `body.overlay-open`
  MutationObserver still watches the same three roots

---

## Where I agree with LingAI without reservation

The architecture read is correct and worth restating: binding Ktor to
`127.0.0.1` only, making no outbound calls, routing all storage through SAF,
and escaping output by default are the right defaults, and the Musicolet-style
static queue with `masterIds` preserved for un-shuffling is a genuinely elegant
model. The wall-clock sleep timer (rather than `postDelayed`, which Doze
stalls) is the kind of detail that usually only shows up after a bug report.
The code is also unusually well-commented — several comments explain *why* a
non-obvious approach was chosen, which is what made auditing the regression
checklist above tractable.
