# AGENTS.md — PoetMusic Project Rules

## MCP Usage Rules

### Context7 (Library Documentation)

Use Context7 MCP to fetch current documentation whenever the user asks about a library, framework, SDK, API, CLI tool, or cloud service used in this project. This includes API syntax, configuration, version migration, library-specific debugging, setup instructions, and CLI tool usage. Use even when you think you know the answer — training data may not reflect recent changes.

**Libraries used in this project:**
- Ktor (server, CIO engine)
- htmx (frontend SPA framework)
- Media3 / ExoPlayer (Android playback)
- GStreamer (Linux playback via gst1-java-core)
- JNA (GTK/WebKitGTK bindings)
- JAudiotagger (desktop tag reading/writing)
- mp3agic (Android tag reading/writing)
- dbus-java (MPRIS D-Bus integration)
- SQLite (Android SQLiteOpenHelper, desktop JDBC)

**When to use Context7:**
- User asks about Ktor routing, configuration, plugins, or CIO engine specifics
- User asks about htmx attributes, extensions, or integration patterns
- User asks about Media3/ExoPlayer session management or playback APIs
- User asks about GStreamer Java bindings or playbin property access
- User asks about JNA struct/mapping patterns for GTK or WebKit
- User asks about any library's latest API changes or migration guides

**Do NOT use Context7 for:**
- Refactoring existing code
- Writing scripts from scratch
- Debugging business logic
- Code review or general programming concepts

**Steps:**
1. `context7_resolve-library-id` with the library name and what to look up
2. Pick the best match by: exact name match, description relevance, code snippet count, source reputation (High/Medium preferred), and benchmark score (higher is better). Use version-specific IDs when the user mentions a version
3. `context7_query-docs` with the selected library ID and a specific, scoped query about a single concept. Make separate calls per distinct concept rather than combining topics
4. Answer using the fetched docs

### Playwright MCP (Browser Testing)

Use Playwright MCP when the user needs to test the htmx frontend in a browser context, verify CSS rendering, debug UI interactions, or validate dark mode behavior. This project's frontend is a single-page htmx app served from an embedded Ktor server.

**When to use Playwright:**
- Verifying htmx partial page updates work correctly
- Testing CSS dark mode and light mode rendering
- Debugging responsive layout issues
- Validating WebSocket or polling-based live state updates
- Taking screenshots for visual regression checks

**When NOT to use Playwright:**
- Kotlin backend logic changes
- Database schema modifications
- Build configuration changes

### Firefox DevTools MCP (Browser Debugging)

Use Firefox DevTools MCP as an alternative to Playwright for inspecting the rendered page DOM, network requests, and console output when debugging the htmx frontend.

## Project Architecture

```
PoetMusic/
├── :core        (Kotlin/JVM — shared views, routing, ports, models)
├── :app         (Android — WebView, Media3, SAF, ID3v2)
└── :desktop     (Linux — GTK/WebKit, GStreamer, JDBC, MPRIS)
```

- `:core` has ZERO Android dependencies (enforced at compile time)
- Frontend assets live in `app/src/main/assets/web/` (served by both platforms)
- The embedded Ktor server binds to `127.0.0.1` only (loopback)
- All view rendering is pure functions returning HTML strings
- Platform ports follow hexagonal architecture: `PlayerPort`, `LibraryStore`, `ScanPort`, `TagPort`, `EqPort`, `HostPort`

## Code Conventions

- Interfaces end in `Port` (e.g., `PlayerPort`, `EqPort`)
- View objects are prefixed `Views*` (e.g., `ViewsLibrary`, `ViewsNowPlaying`)
- Use `runCatching { ... }.getOrDefault()` for I/O operations
- Never hardcode `#3b3651` or similar ink colors in SVG icons — use `currentColor`
- All user-derived strings must be HTML-escaped via `esc()` or `enc()` before interpolation
- Settings keys are constants in companion objects

## Testing

- Tests are in `app/src/test/` using JUnit 4
- View-layer tests call pure rendering functions directly
- Security tests cover XSS prevention in HTML escaping
- Run tests with: `./gradlew :app:test`
- Run linting with: `./gradlew lint` (Android) or manual ktlint checks
