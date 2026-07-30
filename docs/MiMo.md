# MiMo — Refactoring Analysis for PoetMusic

> Generated: July 30, 2026
> Scope: `app/src/main/kotlin/com/musa/poetmusic/server/`, `data/`, `playback/`, `widget/`

---

## Architecture Overview

PoetMusic is an Android music player with an embedded Ktor (CIO) server running inside the app process, serving an htmx single-page frontend rendered in a native WebView.

| Package | Responsibility | Key files |
|---|---|---|
| `server/` | Ktor routes, view objects, page shell, HTML utilities | `PoetServer.kt`, `Views*.kt`, `Shell.kt`, `Html.kt`, `Markdown.kt` |
| `data/` | SQLite database, library scanner, LRC parser, MP3 tag editor | `MusicDatabase.kt`, `Models.kt`, `LibraryScanner.kt`, `TagEditor.kt`, `CacheGuard.kt` |
| `playback/` | Foreground Media3 session, thread-safe player bridge | `PlayerController.kt`, `PlaybackService.kt` |
| `widget/` | Home-screen widget rendering | `WidgetRenderer.kt`, `PoetWidgetProvider.kt` |

---

## 🔴 High-Impact Refactoring Opportunities

### 1. Extract PoetServer Route Groups (~740 → ~300 lines)

**Problem:** `PoetServer.kt` is a monolith. All 30+ route handlers live inside a single `start()` method spanning ~600 lines. Each handler follows the same pattern: parse params → call business logic → respond. This makes the file hard to navigate, test, and extend.

**Proposed split:**

| New file | Routes |
|---|---|
| `RoutesPlayer.kt` | `/api/player/*` (~10 routes: state, play, toggle, next, prev, shuffle, repeat, speed, seek, sleep, favourite, lyrics) |
| `RoutesLibrary.kt` | `/api/library/*`, `/api/tracks/*` (~12 routes: drawer, sub, sort, scan, play-now, play-next, add-queue, add-playlists, delete, favorite) |
| `RoutesPlaylist.kt` | `/api/playlist/*` (~4 routes: create, add, remove, delete) |
| `RoutesSettings.kt` | `/api/settings/*`, `/api/widget/*` (~5 routes: add-folder, remove-folder, accent, theme, dark, pin) |
| `RoutesMedia.kt` | `/api/art/*`, `/api/stream/*` (~2 routes) |
| `PoetServer.kt` | Just `start()`, the art cache, and shared helpers |

**Estimated savings:** ~400 lines, plus dramatically improved file navigability.

---

### 2. Create an `AppSettings` Data Class (4 files, ~12 repetitions)

**Problem:** The same triplet of settings is read independently in four different files:

| File | Lines | Reads |
|---|---|---|
| `PoetServer.kt` | 90–92 | `getSetting("accent", "#b9a5ec")`, `getSetting("theme", "Lavender")`, `getSetting("dark", "0") == "1"` |
| `ViewsSettings.kt` | 32–34 | Identical triplet |
| `MainActivity.kt` | 100–101 | `getSetting("theme", "Lavender")`, `getSetting("dark", "0") == "1"` |
| `WidgetRenderer.kt` | 90–91 | `getSetting("accent", "#b9a5ec")`, `getSetting("theme", "Lavender")` |

**Proposed fix:**

```kotlin
data class AppSettings(
    val accent: String = "#b9a5ec",
    val theme: String = "Lavender",
    val dark: Boolean = false
) {
    companion object {
        fun from(db: MusicDatabase) = AppSettings(
            accent = db.getSetting("accent", "#b9a5ec"),
            theme = db.getSetting("theme", "Lavender"),
            dark = db.getSetting("dark", "0") == "1"
        )
    }
}
```

Then `Shell.page(db)`, `SettingsViews.settingsScreen(db, ...)`, etc. all take a single `AppSettings` instead of three separate params. This eliminates the default-value duplication and typo risk.

---

### 3. Extract `ArtCache` from PoetServer (~30 lines → own class)

**Problem:** The LRU art cache (LinkedHashMap with byte-bounded eviction, eviction/put methods, placeholder art loading) is ~30 lines of hand-rolled cache logic embedded directly in `PoetServer`. This has a clear single responsibility and would benefit from extraction.

**Proposed:** Create `ArtCache.kt` with:
- `get(id: Long): ByteArray?`
- `put(id: Long, bytes: ByteArray)`
- `evict(id: Long)`
- `loadPlaceholder(assets: AssetManager)`

---

### 4. Add `PipelineContext<html>` Helper (removes ~20 suffixes)

**Problem:** Every single route in PoetServer repeats `call.respondText(..., ContentType.Text.Html)`. That's 20+ identical suffixes.

**Proposed fix:**

```kotlin
// In PoetServer or a helpers file:
private suspend fun PipelineContext<Unit, ApplicationCall>.html(body: String) {
    call.respondText(body, ContentType.Text.Html)
}

// Then every route becomes:
html(SomeView.screen(db))
// instead of:
call.respondText(SomeView.screen(db), ContentType.Text.Html)
```

---

### 5. Extract `resolveTrack()` Helper (removes ~7 repetitions)

**Problem:** This exact sequence appears in at least 7 places in `PoetServer.kt` (lines 235, 289, 338, 409, 489, 579, 624):

```kotlin
val t = call.parameters["id"]?.toLongOrNull()?.let(db::track)
    ?: return@post noContent()
```

**Proposed fix:**

```kotlin
private suspend fun PipelineContext<Unit, ApplicationCall>.resolveTrack(): Track? {
    val t = call.parameters["id"]?.toLongOrNull()?.let(db::track)
    if (t == null) { noContent(); return null }
    return t
}
```

---

## 🟡 Medium-Impact Refactoring Opportunities

### 6. Hoist Regex Constants in `Markdown.kt` (Performance)

**Problem:** In `Markdown.inline()`, four `Regex(...)` objects are compiled on **every call** (once per paragraph of markdown). These should be `companion object` val properties:

```kotlin
companion object {
    private val RE_CODE  = Regex("`([^`]+)`")
    private val RE_LINK  = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)")
    private val RE_BOLD  = Regex("\\*\\*([^*]+)\\*\\*")
    private val RE_ITALIC = Regex("(?<!\\*)\\*(?!\\*)([^*]+)\\*(?!\\*)")
}
```

---

### 7. Define Hx-Attribute Constants

**Problem:** Common hx-attribute values appear repeatedly as raw string literals across views:

| Value | Approximate occurrences |
|---|---|
| `"#main-container"` | ~15 times |
| `"#sheet-root"` | ~6 times |
| `"#modal-root"` | ~5 times |
| `hx-swap="none"` | ~25 times |
| `hx-on::after-request="poetFinish()"` | ~10 times |

**Proposed fix:** Define constants in `SharedViews` or `Html.kt`:

```kotlin
const val HX_TARGET_MAIN  = "hx-target=\"#main-container\""
const val HX_TARGET_SHEET = "hx-target=\"#sheet-root\""
const val HX_TARGET_MODAL = "hx-target=\"#modal-root\""
const val HX_SWAP_NONE    = "hx-swap=\"none\""
const val HX_SWAP_HTML    = "hx-swap=\"innerHTML\""
const val HX_FINISH       = "hx-on::after-request=\"poetFinish()\""
```

This prevents typos and makes changing a target selector a single-point change.

---

### 8. MusicDatabase Cursor Mapping Abstraction

**Problem:** Each query method in `MusicDatabase.kt` manually reads from a `Cursor`. The `trackFrom()` private helper is good, but `albums()`, `artists()`, `playlists()`, and `folders()` each have their own manual cursor reading loops with the same `while (c.moveToNext()) out += ...` pattern.

**Proposed fix:**

```kotlin
private inline fun <T> Cursor.mapAll(mapper: (Cursor) -> T): List<T> {
    val out = mutableListOf<T>()
    while (moveToNext()) out += mapper(this)
    return out
}

// Then:
fun albums() = readableDatabase.rawQuery("""...""", null).use { c ->
    c.mapAll { AlbumRow(it.getString(0), it.getString(1), it.getInt(2), it.getLong(3)) }
}
```

---

### 9. Centralize SVG Icons

**Problem:** Play/pause SVGs appear in multiple places:

| Location | Symbols | Format |
|---|---|---|
| `Shell.kt` | `ICON_PLAY_SM`, `ICON_PAUSE_SM`, `ICON_PLAY_LG`, `ICON_PAUSE_LG` | JavaScript string vars |
| `ViewsQueue.kt` | `ICON_QP_PLAY`, `ICON_QP_PAUSE` | Kotlin vals |

These are slightly different sizes but conceptually the same icons. A shared `Icons` object would make them reusable and prevent drift.

---

## Summary: Quick-Win Priorities

| Priority | Refactoring | Est. lines saved | Difficulty |
|---|---|---|---|
| 🔴 1 | Extract PoetServer routes | ~400 | Medium |
| 🔴 2 | `AppSettings` data class | ~30, +consistency | Low |
| 🔴 3 | Extract `ArtCache` | ~30 | Low |
| 🔴 4 | `html()` helper | ~20 | Low |
| 🔴 5 | `resolveTrack()` helper | ~20 | Low |
| 🟡 6 | Hoist Markdown Regex | ~8 | Low |
| 🟡 7 | Hx-attribute constants | ~30 | Low |
| 🟡 8 | Cursor mapping abstraction | ~30 | Low |
| 🟡 9 | Centralize SVG icons | ~20 | Medium |

**Recommended implementation order:** Start with #2 (AppSettings), #4 (html helper), #5 (resolveTrack), and #6 (Markdown regex) — these are all low-effort, high-clarity wins that don't change behavior. Then tackle #1 (route extraction) as the big structural refactor.
