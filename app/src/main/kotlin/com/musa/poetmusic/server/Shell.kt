package com.musa.poetmusic.server

import com.musa.poetmusic.data.AppSettings

/**
 * The single-page shell: the htmx runtime, the persistent header + bottom
 * media tray, and the theme variables. The Poet design-system CSS and the
 * client-side glue JS (state poller, long-press menus, onboarding, theming,
 * toasts) live in assets/web/poet.css and assets/web/poet.js, served through
 * the /assets pipeline; only the theme-dependent :root variables and the
 * window.POET bootstrap stay inline because they are rendered per request.
 */
object Shell {

    val CANVAS_TINTS = mapOf("Lavender" to "#f2effa", "Cream" to "#faf5ec", "Sage" to "#eff6f0")
    val ACCENTS = listOf("#b9a5ec", "#9fd8c0", "#f4b89a", "#a5c9ec")

    fun page(settings: AppSettings, folderCount: Int): String {
        val (accent, theme, dark) = settings
        val bg = CANVAS_TINTS[theme] ?: CANVAS_TINTS.getValue(AppSettings.DEFAULT_THEME)
        return """<!DOCTYPE html>
<html lang="en"${if (dark) """ data-theme="dark"""" else ""}>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">
<title>Poet Music</title>
<script src="/assets/htmx.min.js"></script>
<style>
/* Light color set: every surface, text and input tone the layout uses.
   The html[data-theme="dark"] block in poet.css overrides the same names
   (and outranks :root by specificity), so all components restyle themselves
   when the theme flips. */
:root {
  --accent: $accent;
  --accent-faint: ${accent}2e;
  --accent-soft: ${accent}66;
  --accent-shadow: ${accent}80;
  --bg: $bg;
  --ink: #3b3651;
  --muted: #8a84a3;
  --faint: #b4aecb;
  --lyric-dim: #a49ec0;
  --link: #7a68b8;
  --card-bg: #ffffff;
  --card-glass: rgba(255,255,255,0.85);
  --menu-glass: rgba(255,255,255,0.92);
  --lyrics-glass: rgba(255,255,255,0.7);
  --input-bg: #ffffff;
  --overlay-faint: rgba(59,54,81,0.03);
  --overlay-neutral: rgba(59,54,81,0.06);
  --overlay-strong: rgba(59,54,81,0.18);
  --divider: rgba(59,54,81,0.08);
  --card-border: rgba(59,54,81,0.12);
  --card-border-soft: rgba(59,54,81,0.15);
  --card-border-strong: rgba(59,54,81,0.28);
  --grabber: rgba(59,54,81,0.15);
  --shadow-card: rgba(59,54,81,0.06);
  --track-empty: rgba(59,54,81,0.12);
  --panel-strong: #3b3651;
  --panel-strong-text: #f5f3fa;
}
</style>
<link rel="stylesheet" href="/assets/poet.css">
</head>
<body>
<div id="app">
  <div class="hdr">
    <div class="hdr-brand" onclick="poetGo('/screens/library')">
      <div class="hdr-logo">P</div>
      <div class="hdr-name">Poet</div>
    </div>
    <div style="display:flex; gap:8px;">
      <button id="nav-library" class="navpill active" hx-get="/screens/library" hx-target="#main-container">Library</button>
      <button id="nav-settings" class="navpill" hx-get="/screens/settings" hx-target="#main-container">Settings</button>
    </div>
  </div>

  <!-- Initial screen is loaded once from DOMContentLoaded (see JS below):
       a load-triggered fetch here raced the onboarding redirect and could
       swap the tip away right after it appeared. -->
  <div id="main-container"></div>

  <div id="tray">
    <div id="tray-progress"></div>
    <div class="tray-inner">
      <div class="tray-info" hx-get="/screens/now-playing" hx-target="#main-container">
        <div class="tray-art" id="tray-art">♪</div>
        <div style="min-width:0;">
          <div id="tray-title" style="font-size:13px; font-weight:700; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">Nothing playing</div>
          <div id="tray-artist" style="font-size:11px; color:var(--muted); white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">Tap a song to start</div>
        </div>
      </div>
      <div style="display:flex; align-items:center; gap:6px;">
        <button class="tray-btn" hx-post="/api/player/prev" hx-swap="none">
          <svg width="14" height="11" viewBox="0 0 18 14"><rect x="0" y="0" width="3" height="14" fill="#3b3651"></rect><polygon points="18,0 6,7 18,14" fill="#3b3651"></polygon></svg>
        </button>
        <button class="tray-play" id="tray-play" hx-post="/api/player/toggle" hx-swap="none"></button>
        <button class="tray-btn" hx-post="/api/player/next" hx-swap="none">
          <svg width="14" height="11" viewBox="0 0 18 14"><polygon points="0,0 12,7 0,14" fill="#3b3651"></polygon><rect x="15" y="0" width="3" height="14" fill="#3b3651"></rect></svg>
        </button>
      </div>
    </div>
  </div>

  <div id="cab">
    <div class="cab-inner">
      <button class="cab-x" onclick="poetExitSelect()" aria-label="Exit selection">
        <svg width="13" height="13" viewBox="0 0 12 12"><path d="M1 1 L11 11 M11 1 L1 11" stroke="#f5f3fa" stroke-width="2" stroke-linecap="round"></path></svg>
      </button>
      <div style="min-width:0;">
        <div class="cab-count" id="cab-count">0 selected</div>
        <button class="cab-all" id="cab-all" onclick="poetSelectAll()">Select all</button>
      </div>
      <div class="cab-actions">
        <button class="cab-btn" onclick="poetBatch('play-next')" aria-label="Play next">
          <svg width="18" height="14" viewBox="0 0 18 14"><rect x="0" y="1" width="10" height="2" rx="1" fill="#f5f3fa"></rect><rect x="0" y="6" width="10" height="2" rx="1" fill="#f5f3fa"></rect><rect x="0" y="11" width="7" height="2" rx="1" fill="#f5f3fa"></rect><polygon points="13,4 18,7 13,10" fill="#f5f3fa"></polygon></svg>
        </button>
        <button class="cab-btn" onclick="poetBatch('add-queue')" aria-label="Add to queue">
          <svg width="16" height="16" viewBox="0 0 16 16"><path d="M8 2 V14 M2 8 H14" stroke="#f5f3fa" stroke-width="2" stroke-linecap="round"></path></svg>
        </button>
        <button class="cab-btn" onclick="poetBatchSheet('addplaylist')" aria-label="Add to playlist">
          <svg width="16" height="16" viewBox="0 0 16 16"><path d="M2 4 H14 M2 8 H14 M2 12 H9" stroke="#f5f3fa" stroke-width="2" stroke-linecap="round"></path></svg>
        </button>
        <button class="cab-btn solid" onclick="poetBatchDrawer()" aria-label="More options">⋯</button>
      </div>
    </div>
  </div>

  <div id="queue-root"></div>
  <div id="sheet-root"></div>
  <div id="modal-root"></div>
  <div id="toast"></div>
</div>

<script>
window.POET = { folders: $folderCount, accent: ${jsonStr(accent)}, theme: ${jsonStr(theme)}, themeBg: ${jsonStr(bg)}, dark: ${if (dark) "true" else "false"} };

var ICON_PLAY_SM = '<svg width="14" height="16" viewBox="0 0 22 26" style="margin-left:3px;"><polygon points="0,0 22,13 0,26" fill="#3b3651"></polygon></svg>';
var ICON_PAUSE_SM = '<div style="display:flex; gap:4px;"><div style="width:4px; height:15px; background:#3b3651; border-radius:2px;"></div><div style="width:4px; height:15px; background:#3b3651; border-radius:2px;"></div></div>';
var ICON_PLAY_LG = '<svg width="22" height="26" viewBox="0 0 22 26" style="margin-left:4px;"><polygon points="0,0 22,13 0,26" fill="#3b3651"></polygon></svg>';
var ICON_PAUSE_LG = '<div style="display:flex; gap:6px;"><div style="width:6px; height:24px; background:#3b3651; border-radius:2px;"></div><div style="width:6px; height:24px; background:#3b3651; border-radius:2px;"></div></div>';

/* shuffle button, indexed by mode: 0 play in order, 1 shuffle songs.
   Icons are interpolated from NowPlayingViews so the poller and the server
   render the exact same markup. */
var ICON_SHUFFLE = ['${NowPlayingViews.shuffleIcon(0)}', '${NowPlayingViews.shuffleIcon(1)}'];
var SHUFFLE_TITLES = ['${NowPlayingViews.shuffleTitle(0)}', '${NowPlayingViews.shuffleTitle(1)}'];
/* repeat button, indexed by mode: 1 repeat one, 2 repeat playlist, 3 play
   single & stop; slot 0 falls back to repeat playlist for stale state. */
var ICON_REPEAT = ['${NowPlayingViews.repeatIcon(2)}', '${NowPlayingViews.repeatIcon(1)}', '${NowPlayingViews.repeatIcon(2)}', '${NowPlayingViews.repeatIcon(3)}'];
var REPEAT_TITLES = ['${NowPlayingViews.repeatTitle(2)}', '${NowPlayingViews.repeatTitle(1)}', '${NowPlayingViews.repeatTitle(2)}', '${NowPlayingViews.repeatTitle(3)}'];
</script>
<script src="/assets/poet.js"></script>
</body>
</html>"""
    }
}
