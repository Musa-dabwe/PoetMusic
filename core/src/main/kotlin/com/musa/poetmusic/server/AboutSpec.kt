package com.musa.poetmusic.server

/**
 * The parts of the About screen that genuinely differ between the Android app
 * and the Linux desktop app. Everything else — features, security posture,
 * developer, credits, licence — is the same product and is written once, in
 * [AboutDoc].
 *
 * This exists so the desktop build cannot end up telling users it runs on
 * Media3 in a native WebView with a minimum Android version.
 */
data class AboutSpec(
    val versionName: String,
    /** "offline-first, pastel-themed music player for Android" */
    val tagline: String,
    /** The opening paragraph describing how this build is actually put together. */
    val overview: String,
    /** Tech-stack rows, in display order. */
    val techStack: List<Pair<String, String>>,
    /** The address the embedded server binds to, quoted in two places. */
    val bindAddress: String,
    /** Source-tree tour, one bullet per top-level package. */
    val architecture: List<Pair<String, String>>,
    /** How folders are reached, for the security section. */
    val storageNote: String
)

/** Builds the About markdown from [AboutSpec] plus the parts both builds share. */
object AboutDoc {

    fun markdown(spec: AboutSpec, trackCount: Int, folderCount: Int): String {
        val stack = spec.techStack.joinToString("\n") { (k, v) -> "- **$k** — $v" }
        val arch = spec.architecture.joinToString("\n") { (k, v) -> "- `$k` — $v" }
        val tracks = if (trackCount == 1) "track" else "tracks"
        val folders = if (folderCount == 1) "folder" else "folders"
        return """
# Poet Music

**Version ${spec.versionName}** · ${spec.tagline}

Your library holds **$trackCount $tracks** across **$folderCount $folders**.

${spec.overview}

## Features

- **Local library** — pick any folder; Poet scans it for audio and reads tags, album art and `.lrc` lyric files.
- **Now Playing** — seek bar, playback speed, sleep timer, synced lyrics, favourites and a slide-up queue panel.
- **Musicolet-style queue** — playlists are fixed reference lists; the queue is a temporary working copy with static shuffle, per-song remove and drag-to-reorder.
- **Tag editor** — edit tag details, embed artwork and build synced `.lrc` files, written straight into your music files.
- **Theming** — pastel accent colours, canvas tints and a full dark mode.

## Tech stack

$stack

## Architecture

$arch

The embedded server binds to `${spec.bindAddress}` only and is never reachable from other devices on the network.

## Security & privacy

- **Local-only server** — the Ktor server binds exclusively to `${spec.bindAddress}`; it is not reachable from other devices.
- **No network calls** — the app makes no outbound requests, collects no analytics and transmits no user data.
- **Scoped storage** — ${spec.storageNote}
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
    }
}
