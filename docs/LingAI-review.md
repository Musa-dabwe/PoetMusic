# PoetMusic Repo Review

## Overview

Poet Music is an offline-first, pastel-themed Android music player that uses a novel architecture: an embedded Ktor HTTP server serves an htmx single-page app inside a native WebView, while playback is handled natively via Media3/ExoPlayer in a foreground `MediaSessionService`. It's well-built and thoughtful.

## Strengths

- **Clean architecture** — clear separation of concerns (`server/`, `playback/`, `data/`, `widget/`) with single-responsibility modules
- **Musicolet-style queue model** — smart shuffle/unshuffle logic that preserves the original order around the current track, with play-next insertion
- **Robust persistence** — queue, position, shuffle/repeat/speed modes all survive app restarts via SQLite
- **Thoughtful sleep timer** — uses wall-clock time rather than `postDelayed` (which stalls in Doze), and supports both minute-based and song-count-based timers
- **Inline SVG icons** — all UI icons are inline SVGs, avoiding icon asset bloat
- **Security-conscious** — Ktor binds to `127.0.0.1` only, no outbound network calls, all strings are HTML-escaped
- **Dark mode** — proper CSS variable theming with dark-specific overrides
- **Widget support** — home-screen widget with compact/full variants, synced to playback state

## Areas for Improvement

1. **`MainActivity.kt` is large (258 lines)** — the `onCreate` does too much (WebView setup, permission handling, status bar theming, callback wiring). Extracting some of this into helper methods or a separate coordinator would improve readability.

2. **Thread safety patterns** — `PlayerController` uses `CountDownLatch` + `Handler.post` for thread synchronization in several places (`queueItems()`, `advanceShuffleMode()`, `advanceRepeatMode()`). This is correct but verbose. The `onMain()` helper (line 366) is a cleaner pattern that could be used more consistently.

3. **`Views.kt` is a monolith (1169 lines)** — it contains all HTML rendering, CSS, and JS. Extracting CSS into separate resources and splitting HTML rendering into per-screen/Kotlin files would make it more maintainable.

4. **Security: XSS surface** — while `esc()` and `jsonStr()` are used in most places, there are spots where user-derived strings are interpolated into HTML attributes and `Shell`'s `CANVAS_TINTS` values are interpolated directly into CSS. The CSS var injection (`--accent: $accent`) is particularly risky if user input ever flows there.

5. **No unit tests** — the repo has zero test files. Given the complexity of the queue logic, shuffle algorithm, and `PlayerController` state machine, tests would significantly improve confidence.

6. **Hardcoded version info** — `versionCode = 1`, `versionName = "1.0"` in `app/build.gradle.kts`. This should be managed by CI or a version catalog.

7. **`local.properties` is committed** — it contains SDK paths specific to the developer's machine and should be in `.gitignore`.

8. **`Shell.kt` CSS is inline** — all 530+ lines of CSS live inside a Kotlin string in `Shell.kt`. Moving this to `res/values/styles.xml` or a CSS asset file would separate concerns better.

9. **No ProGuard/R8 rules** — `proguard-rules.pro` exists but has no content. With Ktor + ExoPlayer + MP3agic, you'll need rules to prevent obfuscation of reflection-based APIs.

10. **WebView disk cache clearing** — `onDestroy` clears the WebView disk cache (`clearCache(false)`) which is good for privacy, but the comment says it preserves cookies and DOM storage — worth verifying this doesn't leak cached album art HTTP responses to other processes.

## Verdict

A solid, well-thought-out Android music player with a distinctive architecture. The code is clean and well-commented, the queue/shuffle model is elegant, and the offline-first design is well-executed. The main growth areas are test coverage, CSS/HTML extraction from Kotlin, and tightening the XSS surface in HTML attribute interpolation.