# Poet Music — Pastel Player Widget: Research & Implementation Plan

**Branch:** `claude/notification-widget-plan-yex60c`
**Status:** Plan (no code changes yet)
**Scope:** Home screen app widget in the Poet pastel style, live theme sync with
in-app Settings (accent + canvas tint), resizable layouts, correct playback
wiring, plus the honest verdict on theming the notification-drawer player.
Bonus: disabling pinch-to-zoom (from the same Gemini review).

---

## 1. Where we are today

| Piece | Current state |
|---|---|
| Playback | `PlaybackService` (Media3 `MediaSessionService`) owns ExoPlayer; plain `MediaSession.Builder(this, player).build()` — stock notification, no custom buttons, no branding |
| UI | htmx SPA served by embedded Ktor (`PoetServer`), rendered in a WebView |
| Theming | Accent hex + canvas tint name stored in `MusicDatabase` settings (`accent`, `theme` keys); written by `POST /api/settings/accent` and `POST /api/settings/theme` (`PoetServer.kt:396-406`) |
| "Notification widget" | **Misnamed.** It is an in-app HTML block on the Settings screen (`Views.notificationWidget()`, `Views.kt:664`) — the mini music player from the previous attempt. It never leaves the WebView, so it can never appear in the notification drawer or on the home screen |
| Command bridge | `PlayerController` — a Kotlin `object` (singleton), thread-safe, posts commands to the main thread, refreshes a `Snapshot` every 500 ms |

### Why the htmx approach could not work, and never will

htmx/HTML runs inside the WebView, which only exists while `MainActivity` is
alive. Both the notification drawer and the home screen are rendered by
**other processes** (System UI and the launcher). Android only accepts two
vocabularies there:

- **Notification drawer:** a `Notification` built from the `MediaSession`
  (the OS draws it — see §2).
- **Home screen:** `RemoteViews` — a serialized description of a small set of
  whitelisted XML views (`FrameLayout`, `LinearLayout`, `ImageView`,
  `TextView`, `ImageButton`, `ProgressBar`, …) that the *launcher* inflates in
  its own process.

No HTML, no CSS, no JS, no custom `View` subclasses. Any widget we build is
XML + `RemoteViews`, full stop. The pastel look has to be reproduced with
drawables and runtime tinting (§4.3) — which is entirely doable.

---

## 2. Verdict: can the notification player be pastel-themed?

**On Android 13+ (API 33+): no.** Since Android 13, System UI derives the
media notification entirely from the `MediaSession` — title, artist, artwork,
seekbar, and buttons — and renders it with the **system's own Material You
template**. The scrim color comes from the album art palette, chosen by the
OS. Custom `RemoteViews` layouts, `setColor()`, and `setColorized()` are
ignored for session-backed media notifications. Media3's
`DefaultMediaNotificationProvider` lets us change the small icon, channel
name, and the *set of buttons* — not the colors or layout.

On Android 8–12 the notification is already colorized from album art
(automatic for any notification with a media session attached), which is
"pastel adjacent" when the art is soft, but it is art-driven, not
accent-driven, and that behavior also isn't ours to override reliably.

**Conclusion:** the pastel-themed player the user can place and see must be a
**home screen app widget** (Gemini's "Scenario B"). That surface is 100 % ours:
background color, accent, corner radius, typography color, sizing. The
notification gets the *functional* upgrades that are actually allowed (§6):
a Poet small icon, a proper channel name, and a favorite ♥ custom button.

Sources:
- [Media controls — Android Developers](https://developer.android.com/media/implement/surfaces/mobile) (Android 13 system-rendered media controls)
- [DefaultMediaNotificationProvider — API reference](https://developer.android.com/reference/androidx/media3/session/DefaultMediaNotificationProvider) (what is and isn't customizable)
- [Notification.MediaStyle — API reference](https://developer.android.com/reference/android/app/Notification.MediaStyle) (auto-colorization on O+)
- [androidx/media issue #1163](https://github.com/androidx/media/issues/1163) (custom buttons on 13+ derive from session state, not notification actions)

---

## 3. Corrections to Gemini's suggested implementation

Gemini's Scenario B sketch is directionally right but has three bugs we must
not copy:

1. **`PlayerController.getInstance(context)` does not exist.** Ours is a
   Kotlin `object` — call `PlayerController.togglePlay()` directly.
2. **The dead-process case is unhandled.** A widget outlives the app. If the
   user taps ▶ after Android killed the process, Gemini's
   `BroadcastReceiver → PlayerController` chain runs with `player == null`
   and every command silently no-ops. The tap must (re)start
   `PlaybackService`, wait for the player to attach *and* for
   `restoreState()` to reload the queue, and only then execute.
3. **Broadcast → controller ordering is racy even in-process.** Fix: skip the
   extra receiver entirely and point the `PendingIntent`s **at
   `PlaybackService` itself** (`PendingIntent.getService`). Service creation
   is guaranteed to complete (`onCreate` attaches the player, restores state)
   before `onStartCommand` delivers our action — the ordering problem
   disappears by construction. Widget taps also put the app on the temporary
   background-start allowlist, so `getService` is permitted even on
   Android 12+.
   - One command needs care: `restoreState()` posts asynchronously to the
     main looper, so `onStartCommand` can still beat the queue-restore
     runnable. Add a tiny `PlayerController.dispatch(action)` that runs the
     command via the same `onMain { }` post — it lands on the main-thread
     queue *behind* the restore runnable, preserving order without locks.

---

## 4. Implementation plan — Part A: the home screen widget

### New files

```
app/src/main/kotlin/com/musa/poetmusic/widget/PoetWidgetProvider.kt
app/src/main/kotlin/com/musa/poetmusic/widget/WidgetRenderer.kt
app/src/main/res/xml/poet_widget_info.xml
app/src/main/res/layout/widget_compact.xml      (1-row: art · title/artist · ▶ · ⏭)
app/src/main/res/layout/widget_full.xml         (2-row: adds ⏮, ♥, progress bar)
app/src/main/res/drawable/widget_bg.xml         (rounded rect, tintable)
app/src/main/res/drawable/widget_play_bg.xml    (circle, tintable)
app/src/main/res/drawable/ic_w_play.xml / ic_w_pause.xml / ic_w_next.xml /
                          ic_w_prev.xml / ic_w_heart.xml / ic_w_heart_off.xml
                          (vector drawables traced from the existing SVGs in Shell.kt)
```

### Modified files

```
AndroidManifest.xml                      — <receiver> for the widget provider
PlaybackService.kt                       — onStartCommand action handling; notification polish (§6)
PlayerController.kt                      — dispatch(); state-change hook for widget refresh
PoetServer.kt                            — widget refresh on accent/theme POST; pin-widget endpoint
Views.kt                                 — repurpose the Settings card (§5)
Shell.kt                                 — pinch-zoom meta/CSS (§7); card rename
MainActivity.kt                          — pinch-zoom WebView settings (§7); pin-widget requester
```

### 4.1 Widget metadata (`res/xml/poet_widget_info.xml`)

```xml
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="250dp"  android:minHeight="40dp"
    android:targetCellWidth="4" android:targetCellHeight="1"
    android:maxResizeWidth="500dp" android:maxResizeHeight="200dp"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen"
    android:updatePeriodMillis="0"
    android:initialLayout="@layout/widget_compact"
    android:previewLayout="@layout/widget_full"
    android:description="@string/widget_description" />
```

- `updatePeriodMillis="0"`: we push updates on real state changes (§4.5);
  no wasteful periodic alarms.
- `resizeMode="horizontal|vertical"` + `maxResize*` ⇒ **user-resizable**, the
  explicit requirement.
- `targetCellWidth/Height` (API 31+) and `minWidth/minHeight` (API 26–30)
  cover both sizing systems; minSdk is 26.

### 4.2 Resizable = responsive layouts

Two layouts, chosen by available size:

- **compact** (height < ~100dp): 4×1 pill — album art (44dp, rounded 10dp),
  title + artist stack, play/pause in the accent circle, next.
- **full** (height ≥ ~100dp): 4×2 card — art 64dp, title/artist, thin
  accent `ProgressBar`, then ⏮ ▶ ⏭ ♥ row (mirrors the in-app tray + the
  Settings preview block).

Selection mechanism:

- **API 31+:** `RemoteViews(mapOf(SizeF(250f, 40f) to compact, SizeF(250f, 110f) to full))`
  — the launcher picks per size, live during the resize gesture.
- **API 26–30:** override `onAppWidgetOptionsChanged()` and choose from
  `OPTION_APPWIDGET_MIN_HEIGHT`.

Both paths funnel through one `WidgetRenderer.render(context, sizeBucket)` so
the theming logic exists exactly once.

### 4.3 Pastel theming + live sync with in-app Settings

Design tokens (all already in `Shell.kt`):

| Token | Source | Widget application |
|---|---|---|
| Canvas tint | `CANVAS_TINTS[db.getSetting("theme")]` — `#f2effa` / `#faf5ec` / `#eff6f0` | widget card background |
| Accent | `db.getSetting("accent")` — `#b9a5ec` / `#9fd8c0` / `#f4b89a` / `#a5c9ec` | play-button circle, progress bar, art placeholder |
| Ink | `#3b3651` (const) | title text, icons |
| Muted | `#8a84a3` (const) | artist text |

`RemoteViews` cannot apply arbitrary background colors to a rounded drawable
directly (`setBackgroundColor` would flatten the corners), so the standard
trick: the background is a full-bleed `ImageView` whose `src` is the rounded
rect `widget_bg.xml`, tinted at render time —

```kotlin
views.setInt(R.id.widget_bg, "setColorFilter", canvasColor)   // card = canvas tint
views.setInt(R.id.play_bg,   "setColorFilter", accentColor)   // circle = accent
views.setTextColor(R.id.title,  INK)
views.setTextColor(R.id.artist, MUTED)
```

Corner radius: `@android:dimen/system_app_widget_background_radius` on
API 31+ (matches launcher clipping), fixed `16dp` fallback for 26–30 via a
dimen overlay in `values-v31/`.

**Live sync — the key requirement.** The widget provider runs **in the app's
own process**, and both settings routes already run there too. So:

- `POST /api/settings/accent` and `POST /api/settings/theme`
  (`PoetServer.kt:396-406`): after `db.setSetting(...)`, call
  `WidgetRenderer.pushUpdate(appContext)`. `PoetServer.start(this, db)`
  already receives the `Application`, so the context is at hand.
- `pushUpdate` re-renders and calls
  `AppWidgetManager.updateAppWidget(ids, views)` for all instances.

Result: tapping a swatch or tint pill in Settings recolors the home screen
widget within the same second — no polling, no persistence detour beyond the
existing settings table (which also makes the theme survive reboots, since
`onUpdate` re-reads the db).

### 4.4 Playback wiring (per §3, corrected)

```
[widget button] --PendingIntent.getService--> [PlaybackService.onStartCommand]
                                                    | action = poet.widget.PLAY_PAUSE / NEXT / PREV / FAVOURITE
                                                    v
                                          PlayerController.dispatch { ... }
[widget body]  --PendingIntent.getActivity--> [MainActivity]  (opens the app)
```

- `PlaybackService.onStartCommand`: call `super` first (Media3 media-button
  handling), then map our four actions to the existing `PlayerController`
  calls (`togglePlay`, `next`, `previous`) and the favorite toggle
  (`db.setFavorite`, mirroring `POST /api/player/favourite`).
- Cold start works by construction: `onCreate` (attach + `restoreState`)
  always precedes `onStartCommand`.
- Each `PendingIntent` gets a distinct `requestCode` +
  `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE` (Gemini's snippet reused
  requestCode 0 for everything — collisions overwrite each other).

### 4.5 Keeping the widget current (without burning battery)

`PlayerController`'s 500 ms refresher already computes fresh snapshots. Add a
cheap change detector there: when `(trackId, playing, favorite)` differs from
the last *pushed* widget state → `WidgetRenderer.pushUpdate(context)`.
Never push on mere position ticks — `RemoteViews` updates are IPC-heavy and
binder-transaction-limited; the progress bar updates only when a push happens
for another reason (targeting track changes and play/pause flips is exactly
the cadence users perceive).

Other update triggers:

- `onUpdate()` / `onEnabled()` — launcher-driven (placement, reboot, resize).
- `PlaybackService.onDestroy` — final push rendering the "paused / Nothing
  playing" state so a dead process never leaves a stale ▶/⏸ icon.

Album art: decode from the track URI with `MediaMetadataRetriever` (same
source `LibraryScanner` uses), downscaled to ≤128 px, cached alongside the
snapshot; fall back to the accent-tinted ♪ placeholder — same behavior as the
tray. Never ship full-resolution art in `RemoteViews` (binder limit).

### 4.6 Manifest addition

```xml
<receiver android:name=".widget.PoetWidgetProvider" android:exported="false">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data android:name="android.appwidget.provider"
               android:resource="@xml/poet_widget_info" />
</receiver>
```

---

## 5. Repurpose the Settings-screen mini player

The previous attempt's block (`Views.notificationWidget()`) is wrong as a
*feature* but perfect as a **preview**: it already renders the exact pastel
card the widget will reproduce, live-updated by the state poller.

- Rename the card `Notification widget` → **`Home screen widget`**; new
  sub-line: *"Pastel player for your launcher — colors follow your accent
  and canvas tint."* (`Views.kt:605-609`, `Shell.kt` CSS comment labels.)
- Keep the block as the in-app preview of the widget.
- Add an **"Add to home screen"** button: new `POST /api/widget/pin` route →
  a `PoetServer.pinWidgetRequester` callback (same pattern as
  `addFolderRequester`) → `MainActivity` calls
  `AppWidgetManager.requestPinAppWidget(...)` when
  `isRequestPinAppWidgetSupported` (API 26+, matches minSdk); toast a hint to
  long-press the launcher otherwise.

---

## 6. Notification polish (what Android actually allows)

Cheap wins in `PlaybackService`, no fighting the OS:

1. **Favorite ♥ custom button** via
   `CommandButton` + `SessionCommand("poet.FAVOURITE")` passed to
   `MediaSession.Builder.setCustomLayout(...)`, handled in a
   `MediaSession.Callback` (Media3 1.7.1 supports this; shows on the
   notification and on Wear/Auto surfaces).
2. **Poet small icon + channel name** via a `DefaultMediaNotificationProvider`
   configured with `setSmallIcon(R.drawable.ic_stat_poet)` and a
   "Playback" channel label — replaces the generic icon in the status bar.
3. Explicitly **not** attempted: notification background/accent colors on
   13+ (see §2 verdict).

---

## 7. Bonus from the same review: disable pinch-to-zoom

Three layers, all trivial (Gemini's suggestion is correct here):

1. **`MainActivity.kt`** (`with(web.settings)` block):
   `setSupportZoom(false)`, `builtInZoomControls = false`,
   `displayZoomControls = false`.
2. **`Shell.kt` viewport meta** → add
   `maximum-scale=1.0, user-scalable=no` to the existing
   `width=device-width, initial-scale=1, viewport-fit=cover`.
3. **`Shell.kt` CSS** → `body { touch-action: manipulation; }` (also kills
   the double-tap-zoom delay).

---

## 8. Build order & verification

| Phase | Contents | Done when |
|---|---|---|
| 1 | Drawables, layouts, widget info XML, manifest receiver, static `WidgetRenderer` | Widget places on launcher, default pastel, opens app on tap |
| 2 | Service `PendingIntent`s + `onStartCommand` actions + `dispatch()` | Play/pause/next/prev/♥ work — including after force-stopping the app |
| 3 | Theme sync (settings routes → push) + snapshot change detection | Swatch tap in Settings recolors widget instantly; track changes update title/art |
| 4 | Responsive sizes (SizeF map + options-changed fallback) + pin button | Compact ↔ full swap while resizing; "Add to home screen" pins |
| 5 | Notification polish (§6) + pinch-zoom (§7) | ♥ visible in the notification; page no longer zooms |

Manual test matrix (no emulator in CI): API 26 or 28 device/emulator (legacy
sizing + non-system-rendered notification), API 31–34 (SizeF map, system
media controls), plus: reboot persistence, process-death tap recovery, all
4 accents × 3 tints, 1×4 → 2×4 resize, RTL sanity.

---

## 9. Explicit non-goals

- HTML/htmx anywhere in the widget path (impossible — §1).
- Custom colors/layout for the Android 13+ media notification (blocked by
  the OS — §2).
- Lock screen widget targets (`widgetCategory="keyguard"` is dead post-API 21;
  the lock screen surface *is* the system media notification).
- Glance/Compose widget library — it would add a Compose dependency to an
  app that deliberately has none; classic `RemoteViews` is ~200 lines here.
