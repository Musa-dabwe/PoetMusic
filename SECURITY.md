# Security Policy

## Supported Versions

| Version | Supported |
| ------- | --------- |
| 1.x     | ✅        |

## Security Model

Poet Music is an offline-first app. Key points of its security posture:

- **Local-only server** — the embedded Ktor server binds exclusively to
  `127.0.0.1:8080`; it is not reachable from other devices. `INTERNET` permission is
  required only for this loopback communication between the WebView and the server.
- **No network calls** — the app makes no outbound network requests, collects no
  analytics, and transmits no user data.
- **Scoped storage** — music folders are accessed through the Storage Access
  Framework (SAF) with user-granted, persistable URI permissions only. The app never
  requests broad storage permissions.
- **Escaped output** — all user- and file-derived strings (titles, artists, paths)
  are HTML/JSON-escaped before rendering to prevent markup injection in the WebView.
- **Local data** — the library index, playlists and settings live in a private
  SQLite database in app-internal storage.

## Reporting a Vulnerability

If you discover a security vulnerability, please open a
[GitHub security advisory](https://github.com/Musa-dabwe/PoetMusic/security/advisories/new)
or open an issue with the `security` label (avoid including exploit details in public
issues). Reports are typically triaged within 7 days.

Please include:

1. A description of the vulnerability and its impact.
2. Steps to reproduce (device / Android version, app version).
3. Any suggested remediation, if known.
