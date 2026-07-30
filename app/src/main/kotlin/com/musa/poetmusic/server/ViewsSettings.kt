package com.musa.poetmusic.server

import com.musa.poetmusic.BuildConfig
import com.musa.poetmusic.data.LibraryScanner
import com.musa.poetmusic.data.MusicDatabase

/** Settings screen, scan card and the About screen. */
object SettingsViews {

    fun settingsScreen(db: MusicDatabase, showTip: Boolean): String {
        val folders = db.folders()
        val folderRows = folders.joinToString("") { f ->
            """
            <div class="folder-row">
              <span style="font-size:14px;">▣</span>
              <span class="folder-path">${esc(f.displayPath)}</span>
              <button hx-get="/partial/confirm-folder/${f.id}" hx-target="#modal-root" hx-swap="innerHTML"
                      style="border:none; background:transparent; cursor:pointer; color:var(--muted); font-size:15px; padding:2px 6px; border-radius:6px;">✕</button>
            </div>"""
        }
        val tip = if (showTip) """
            <div class="tip-banner" id="tip-banner">
              <div class="tip-arrow"></div>
              <div class="tip-body">
                <div style="font-size:14px; font-weight:700; margin-bottom:4px;">Start here</div>
                <div style="font-size:13px; line-height:1.45; color:rgba(245,243,250,0.85);">Add a folder so Poet can find your music. You can add more anytime.</div>
                <button onclick="dismissTip()" style="border:none; cursor:pointer; font-family:inherit; margin-top:12px; padding:8px 14px; border-radius:99px; background:var(--accent); color:#3b3651; font-size:13px; font-weight:700;">Got it, don't show again</button>
              </div>
            </div>""" else ""
        val shield = if (showTip) """<div id="tip-shield" onclick="dismissTip()"></div>""" else ""

        val accent = db.getSetting("accent", "#b9a5ec")
        val theme = db.getSetting("theme", "Lavender")
        val dark = db.getSetting("dark", "0") == "1"
        val swatches = Shell.ACCENTS.joinToString("") { c ->
            """<button class="swatch${if (c == accent) " on" else ""}" data-c="$c" style="background:$c;" onclick="setAccent('$c')"></button>"""
        }
        val tints = Shell.CANVAS_TINTS.entries.joinToString("") { (name, bg) ->
            """<button class="tint-pill${if (name == theme) " on" else ""}" data-n="$name" style="background:$bg;" onclick="setTheme('$name','$bg')">$name</button>"""
        }

        return """
        <div class="screen" data-screen="settings" style="display:flex; flex-direction:column; gap:16px;">
          <div style="font-size:22px; font-weight:700; letter-spacing:-0.02em;">Settings</div>

          <div class="card" id="folder-card" style="position:relative; z-index:${if (showTip) 50 else 1};">
            <div class="card-title">Music folders</div>
            <div class="card-sub">Local directories scanned for audio files</div>
            <div style="display:flex; flex-direction:column; gap:8px; margin-bottom:14px;">
              ${folderRows.ifBlank { """<div style="font-size:12px; color:var(--muted); padding:4px 2px;">No folders mapped yet.</div>""" }}
            </div>
            <button class="btn-primary" style="width:100%; justify-content:center;" hx-post="/api/settings/add-folder" hx-swap="none" onclick="localStorage.setItem('poet-tip-dismissed','1')">+ Add folder directory</button>
            $tip
          </div>
          $shield

          <div class="card">
            <div class="card-title">Library scan</div>
            <div id="scan-card">${scanCard(db)}</div>
          </div>

          <div class="card">
            <div class="card-title" style="margin-bottom:4px;">Appearance</div>
            <div class="card-sub">Switch between the light and dark colour set</div>
            <div style="display:flex; gap:8px;">
              <button class="theme-opt${if (!dark) " on" else ""}" data-dark="0" onclick="setDark(false)">
                <span style="font-size:15px;">☀</span> Light
              </button>
              <button class="theme-opt${if (dark) " on" else ""}" data-dark="1" onclick="setDark(true)">
                <span style="font-size:15px;">☾</span> Dark
              </button>
            </div>
          </div>

          <div class="card">
            <div class="card-title" style="margin-bottom:12px;">Accent color</div>
            <div style="display:flex; gap:12px; margin-bottom:18px;">$swatches</div>
            <div class="card-title" style="margin-bottom:12px;">Canvas tint</div>
            <div class="card-sub">Used in light mode</div>
            <div style="display:flex; gap:8px; flex-wrap:wrap;">$tints</div>
          </div>

          <div class="card" style="cursor:pointer;" hx-get="/screens/about" hx-target="#main-container">
            <div style="display:flex; align-items:center; justify-content:space-between; gap:12px;">
              <div style="min-width:0;">
                <div class="card-title" style="margin-bottom:2px;">About Poet Music</div>
                <div class="card-sub" style="margin-bottom:0;">Version, tech stack, license &amp; developer</div>
              </div>
              <span style="color:var(--faint); font-size:20px; flex-shrink:0;">›</span>
            </div>
          </div>
        </div>"""
    }

    // ---------------- about ----------------

    /**
     * The About screen shown from Settings, built from the project's README,
     * license, security policy, tech stack and developer info and rendered
     * through the in-app [Markdown] renderer so it follows the active theme.
     */
    fun aboutScreen(db: MusicDatabase): String {
        val trackCount = db.trackCount()
        val folderCount = db.folders().size
        val md = """
# Poet Music

**Version ${BuildConfig.VERSION_NAME}** · offline-first, pastel-themed music player for Android.

Your library holds **$trackCount ${if (trackCount == 1) "track" else "tracks"}** across **$folderCount ${if (folderCount == 1) "folder" else "folders"}**.

Poet Music is an offline-first music player. The UI is an [htmx](https://htmx.org) single-page app served by an embedded [Ktor](https://ktor.io) server running inside the app process and rendered in a native WebView. Playback is handled natively by Media3 / ExoPlayer through a foreground `MediaSessionService`, so music keeps playing with the screen off and shows lockscreen / notification controls.

## Features

- **Local library** — pick any folder with the system file picker; Poet scans it for audio and reads tags, album art and `.lrc` lyric files.
- **Now Playing** — seek bar, playback speed, sleep timer, synced lyrics, favourites and a slide-up queue panel.
- **Musicolet-style queue** — playlists are fixed reference lists; the queue is a temporary working copy with static shuffle, per-song remove and drag-to-reorder.
- **Tag editor** — edit ID3v2 details, embed artwork and build synced `.lrc` files, written straight into MP3 files.
- **Theming** — pastel accent colours, canvas tints and a full dark mode.

## Tech stack

- **Language** — Kotlin
- **UI** — htmx single-page app in a native WebView
- **Server** — embedded Ktor (CIO) bound to `127.0.0.1:8080`
- **Playback** — Media3 / ExoPlayer in a foreground `MediaSessionService`
- **Storage** — SQLite (tracks, folders, playlists, settings)
- **Minimum Android** — 8.0 (API 26)

## Architecture

- `server/` — Ktor routes, the page shell + client JS, and server-rendered views.
- `playback/` — the foreground Media3 session and a thread-safe player bridge.
- `data/` — the SQLite database, library scanner, LRC parser and MP3 tag editor.

The embedded server binds to `127.0.0.1` only and is never reachable from other devices on the network.

## Security & privacy

- **Local-only server** — the Ktor server binds exclusively to `127.0.0.1:8080`; it is not reachable from other devices.
- **No network calls** — the app makes no outbound requests, collects no analytics and transmits no user data.
- **Scoped storage** — folders are accessed through the Storage Access Framework with user-granted permissions only.
- **Escaped output** — all user- and file-derived strings are escaped before rendering to prevent markup injection.

To report a vulnerability, open a [GitHub security advisory](https://github.com/Musa-dabwe/PoetMusic/security/advisories/new) or an issue with the `security` label.

## Developer

Built by **Musa-dabwe** (Fackson Musadabwe Mutetesha).

- **GitHub** — [Musa-dabwe](https://github.com/Musa-dabwe)
- **Repository** — [Musa-dabwe/PoetMusic](https://github.com/Musa-dabwe/PoetMusic)

## Credits

- **Placeholder cover art** — [Designed by rawpixel.com / Freepik](http://www.freepik.com). The original image was modified (cropped/resized) for in-app use, as permitted by the Freepik free license.

## License

Licensed under the **Apache License 2.0**.

Copyright © 2026 Fackson Musadabwe Mutetesha. Licensed under the Apache License, Version 2.0; you may not use this software except in compliance with the License. The software is distributed on an "AS IS" basis, without warranties or conditions of any kind.
""".trimIndent()

        return """
        <div class="screen" data-screen="about">
          <button class="backlink" hx-get="/screens/settings" hx-target="#main-container">← Settings</button>
          <div class="card">${Markdown.render(md)}</div>
        </div>"""
    }

    /**
     * Scan card body. Only polls /partial/scan while a scan is actually
     * running: the polling wrapper is part of the swapped content, so the
     * every-2s DOM churn stops as soon as the scan finishes instead of
     * tearing the card down forever while the user reads Settings.
     */
    fun scanCard(db: MusicDatabase): String {
        val scanning = LibraryScanner.isScanning
        val status = if (scanning) esc(LibraryScanner.progressText)
        else esc(db.getSetting("last_scan", "Not scanned yet"))
        val body = """
        <div class="card-sub">$status</div>
        <button class="btn-outline" hx-post="/api/library/scan" hx-target="#scan-card" hx-swap="innerHTML" ${if (scanning) "disabled" else ""}>
          ${if (scanning) """<span class="spinner"></span> Scanning library…""" else """<span style="font-size:15px;">⟳</span> Trigger library scan"""}
        </button>"""
        return if (scanning)
            """<div hx-get="/partial/scan" hx-trigger="every 2s" hx-target="#scan-card" hx-swap="innerHTML">$body</div>"""
        else body
    }
}
