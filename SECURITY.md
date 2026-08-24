# Security Policy

## Supported Versions

| Version | Supported |
| ------- | --------- |
| 1.x     | ✅        |

## Security Model

Poet Music is an offline-first app on both platforms. Key points of its posture:

- **Local-only server** — the embedded Ktor server binds exclusively to loopback:
  `127.0.0.1:8080` on Android, and `127.0.0.1` on a port the OS picks at random on
  Linux, because a desktop is multi-user and a fixed port collides. It is not reachable
  from other devices. On Android the `INTERNET` permission is required only for this
  loopback communication between the WebView and the server. Note: other local processes
  running under the same user account can reach the loopback server, but the app collects
  no sensitive data (no accounts, no analytics, no personal identifiers beyond file names
  and tags), making this an acceptable trade-off for the simplicity of loopback-only
  binding.
- **No network calls** — the app makes no outbound network requests, collects no
  analytics, and transmits no user data. The WebView / WebKit view loads nothing from
  outside the app: fonts, scripts and stylesheets are all served from the bundled
  assets, and links to external sites are handed to the system browser rather than
  opened in-app.
- **Scoped file access** — Poet reads only folders you add and writes only to its own
  data directory. Android goes through the Storage Access Framework with user-granted,
  persistable URI permissions, and never requests broad storage permissions. Linux reads
  the paths chosen in the GTK folder chooser.
- **Escaped output** — all user- and file-derived strings (titles, artists, paths) are
  HTML/JSON-escaped before rendering, to prevent markup injection in the web view.
- **Local data** — the library index, playlists, play history and settings live in a
  private SQLite database: app-internal storage on Android,
  `$XDG_DATA_HOME/poet-music/library.db` on Linux.
- **Tag writes are in-place** — the tag editor modifies the audio files you point it at,
  and the optional "rename from tags" pattern renames them. Nothing else on disk is
  touched.

### Linux specifics

- **MPRIS** — the desktop build publishes `org.mpris.MediaPlayer2` on the **session**
  bus, which is per-user and not shared between accounts. What it exposes is what any
  media applet shows: the current track's tags, position, and the transport controls.
  Anything able to reach your session bus can therefore read what you are playing and
  control playback — the same trade every Linux media player makes for panel and
  media-key support. Cover art is staged as files under `$XDG_CACHE_HOME/poet-music/`
  with your account's own permissions.
- **The `.deb`** — carries its own Java runtime, and binds the system's GTK 3,
  WebKitGTK and GStreamer rather than bundling them, so those track your distribution's
  security updates. Its maintainer scripts stop a running instance before removal, copy
  an AppStream metadata file into `/usr/share/metainfo` on install, and remove it again
  on uninstall.

## Reporting a Vulnerability

If you discover a security vulnerability, please open a
[GitHub security advisory](https://github.com/Musa-dabwe/PoetMusic/security/advisories/new)
or open an issue with the `security` label (avoid including exploit details in public
issues). Reports are typically triaged within 7 days.

Please include:

1. A description of the vulnerability and its impact.
2. Steps to reproduce, and which build it affects — Android (device and OS version) or
   Linux (distribution and desktop) — plus the app version.
3. Any suggested remediation, if known.
