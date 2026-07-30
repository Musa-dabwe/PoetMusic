package com.musa.poetmusic.server

/**
 * The single-page shell: Poet design-system CSS, the htmx runtime, the
 * persistent header + bottom media tray, and the client-side glue JS
 * (state poller, long-press menus, onboarding, theming, toasts).
 */
object Shell {

    val CANVAS_TINTS = mapOf("Lavender" to "#f2effa", "Cream" to "#faf5ec", "Sage" to "#eff6f0")
    val ACCENTS = listOf("#b9a5ec", "#9fd8c0", "#f4b89a", "#a5c9ec")

    fun page(accent: String, theme: String, folderCount: Int, dark: Boolean): String {
        val bg = CANVAS_TINTS[theme] ?: CANVAS_TINTS.getValue("Lavender")
        return """<!DOCTYPE html>
<html lang="en"${if (dark) """ data-theme="dark"""" else ""}>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">
<title>Poet Music</title>
<script src="/assets/htmx.min.js"></script>
<style>
@font-face { font-family:'Outfit'; font-style:normal; font-weight:400 700; font-display:swap;
  src:url('/assets/outfit-latin.woff2') format('woff2');
  unicode-range:U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+2000-206F,U+2074,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD; }
@font-face { font-family:'Outfit'; font-style:normal; font-weight:400 700; font-display:swap;
  src:url('/assets/outfit-latin-ext.woff2') format('woff2');
  unicode-range:U+0100-024F,U+0259,U+1E00-1EFF,U+2020,U+20A0-20AB,U+20AD-20CF,U+2113,U+2C60-2C7F,U+A720-A7FF; }

/* Light color set: every surface, text and input tone the layout uses.
   The dark block below overrides the same names, so all components
   restyle themselves when the theme flips. */
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
/* html[data-theme] outranks :root, and setDark() drops any inline --bg,
   so the dark canvas wins over the selected light canvas tint. */
html[data-theme="dark"] {
  --bg: #16151d;
  --ink: #f0f0f5;
  --muted: #a6a2b8;
  --faint: #7b7690;
  --lyric-dim: #7b7690;
  --link: #c3b2f0;
  --card-bg: #1e1e24;
  /* opaque: dark mode runs without blur, so translucent glass would show
     smeared content behind it instead of a frosted surface */
  --card-glass: #1e1e24;
  --menu-glass: #26262e;
  --lyrics-glass: #1e1e24;
  --input-bg: #26262e;
  --overlay-faint: rgba(255,255,255,0.04);
  --overlay-neutral: rgba(255,255,255,0.07);
  --overlay-strong: rgba(255,255,255,0.18);
  --divider: rgba(255,255,255,0.08);
  --card-border: rgba(255,255,255,0.12);
  --card-border-soft: rgba(255,255,255,0.14);
  --card-border-strong: rgba(255,255,255,0.3);
  --grabber: rgba(255,255,255,0.18);
  --shadow-card: rgba(0,0,0,0.45);
  --track-empty: rgba(255,255,255,0.16);
  --panel-strong: #26262e;
  --panel-strong-text: #f0f0f5;
}
html[data-theme="dark"] input::placeholder, html[data-theme="dark"] textarea::placeholder { color: #6f6b82; }
/* accent-faint chips lose too much contrast over dark cards */
html[data-theme="dark"] .dicon { background: var(--accent-soft); }
/* dark mode: WebView compositing bleeds blurred layers (button/cover glass
   halos leak outside their bounds), so every backdrop blur is disabled and
   the glass surfaces above are opaque instead */
html[data-theme="dark"] .sheet-shield, html[data-theme="dark"] .center-shield,
html[data-theme="dark"] .queue-shield, html[data-theme="dark"] .menu,
html[data-theme="dark"] .lyrics-deck, html[data-theme="dark"] #tray,
html[data-theme="dark"] #modal-root .modal-shield { backdrop-filter:none; -webkit-backdrop-filter:none; }
* { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
html, body { margin:0; padding:0; }
body { background: var(--bg); font-family:'Outfit', sans-serif; color: var(--ink); transition: background 0.25s, color 0.25s;
  -webkit-user-select:none; user-select:none;
  /* blocks pinch and double-tap zoom while keeping scroll + tap responsive */
  touch-action: manipulation; }
input, textarea { -webkit-user-select:text; user-select:text; }
/* while any overlay (drawer/sheet/queue/modal) is open the page behind must
   not scroll — overscroll-behavior on the overlay only helps once the overlay
   itself overflows, so short drawers need the scroller frozen too */
body.overlay-open { overflow:hidden; }
button { font-family: inherit; color: var(--ink); }
/* theme flip: let the big surfaces cross-fade instead of snapping */
.card, .drawer, .sheet, .modal, .info-card, .confirm-card, .editor-sheet, #tray, .queue-panel {
  transition: background-color 0.25s, color 0.25s, border-color 0.25s; }
@keyframes poet-spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
@keyframes poet-bob { 0%,100% { transform: translateY(0); } 50% { transform: translateY(6px); } }
@keyframes poet-fade { from { opacity:0; transform: translateY(6px);} to { opacity:1; transform: translateY(0);} }
@keyframes poet-fade-in { from { opacity:0; } to { opacity:1; } }
@keyframes poet-sheet-up { from { transform: translate(-50%,100%); } to { transform: translate(-50%,0); } }
@keyframes poet-pop { 0% { transform: scale(0.7); } 60% { transform: scale(1.15); } 100% { transform: scale(1); } }

#app { width:100%; max-width:480px; min-height:100vh; margin:0 auto; position:relative; display:flex; flex-direction:column; }
#main-container { flex:1; padding:6px 20px 130px 20px; }
.screen { animation: poet-fade 0.18s ease-out; }

/* header */
.hdr { display:flex; align-items:center; justify-content:space-between; padding:18px 20px 10px 20px; }
.hdr-brand { display:flex; align-items:center; gap:10px; cursor:pointer; }
.hdr-logo { width:30px; height:30px; border-radius:9px; background:var(--accent); display:flex; align-items:center; justify-content:center; font-weight:700; font-size:15px; color:#3b3651; }
.hdr-name { font-size:20px; font-weight:700; letter-spacing:-0.02em; }
.navpill { border:none; cursor:pointer; padding:8px 14px; border-radius:99px; font-size:13px; font-weight:600; background:transparent; transition: background 0.15s, transform 0.12s; }
.navpill:active { transform: scale(0.95); }
.navpill.active { background: var(--accent-faint); }

/* generic */
.pill { border:none; cursor:pointer; font-size:13px; font-weight:600; padding:8px 14px; border-radius:99px; background:var(--overlay-neutral); transition: background 0.15s, transform 0.12s; }
.pill:active { transform: scale(0.95); }
.pill.active { background: var(--accent); color:#3b3651; }
.btn-primary { border:none; cursor:pointer; display:inline-flex; align-items:center; gap:8px; padding:10px 18px; border-radius:12px; background:var(--accent); color:#3b3651; font-size:14px; font-weight:700; box-shadow:0 2px 8px var(--accent-shadow); transition: transform 0.12s; }
.btn-primary:active { transform: scale(0.95); }
.btn-outline { border:1.5px solid var(--accent); cursor:pointer; display:inline-flex; align-items:center; gap:8px; padding:10px 18px; border-radius:12px; background:var(--card-bg); font-size:14px; font-weight:700; transition: transform 0.12s; }
.btn-outline:active { transform: scale(0.95); }
.card { background:var(--card-bg); border-radius:18px; padding:18px; box-shadow:0 2px 10px var(--shadow-card); }
.card-title { font-size:15px; font-weight:700; margin-bottom:4px; }
.card-sub { font-size:12px; color:var(--muted); margin-bottom:12px; }
.backlink { border:none; background:transparent; cursor:pointer; font-size:13px; font-weight:600; color:var(--muted); padding:6px 0; margin-bottom:10px; display:inline-block; }

/* song rows */
.row { position:relative; display:flex; align-items:center; gap:12px; padding:8px 10px; border-radius:14px; cursor:pointer; transition: transform 0.1s, background 0.2s; }
.row:active { transform: scale(0.98); }
.row.playing { background: var(--accent-faint); }
.row-art { width:44px; height:44px; border-radius:10px; flex-shrink:0; display:flex; align-items:center; justify-content:center; font-size:11px; font-weight:600; color:rgba(59,54,81,0.55); overflow:hidden; }
.row-art img { width:100%; height:100%; object-fit:cover; }
.row-main { flex:1; min-width:0; }
.row-title { font-size:14px; font-weight:600; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.row-sub { font-size:12px; color:var(--muted); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.row-dur { font-size:12px; color:var(--muted); font-variant-numeric:tabular-nums; }
.row-menu-btn { border:none; background:transparent; cursor:pointer; font-size:18px; color:var(--muted); padding:6px 8px; border-radius:8px; line-height:1; }
.row-menu-btn:active { transform: scale(0.9); }

/* multi-select: checkbox + selected state (toggled by body.select-mode) */
.row-check { width:22px; height:22px; border-radius:7px; flex-shrink:0; border:2px solid var(--card-border-strong);
  display:none; align-items:center; justify-content:center; }
.row-check svg { display:none; }
body.select-mode .row-check { display:flex; }
body.select-mode .row-menu-btn { display:none; }
.row.selected { background: var(--accent-soft); }
.row.selected .row-check { border-color:var(--ink); background:var(--accent); }
.row.selected .row-check svg { display:block; }

/* contextual action bar (multi-select) */
#cab { position:fixed; bottom:0; left:50%; transform:translateX(-50%); width:100%; max-width:480px; z-index:60;
  background:var(--panel-strong); padding:12px 14px calc(14px + env(safe-area-inset-bottom)) 14px;
  box-shadow:0 -8px 28px rgba(59,54,81,0.35); animation:poet-sheet-up 0.22s cubic-bezier(0.2,0.9,0.3,1); display:none; }
body.select-mode #cab { display:block; }
.cab-inner { display:flex; align-items:center; gap:10px; }
.cab-x { border:none; background:rgba(245,243,250,0.12); cursor:pointer; width:38px; height:38px; border-radius:11px;
  display:flex; align-items:center; justify-content:center; flex-shrink:0; }
.cab-x:active { transform:scale(0.9); }
.cab-count { font-size:15px; font-weight:700; color:#f5f3fa; }
.cab-all { border:none; background:transparent; cursor:pointer; font-size:12px; font-weight:600; color:rgba(245,243,250,0.7); padding:0; }
.cab-actions { display:flex; align-items:center; gap:4px; margin-left:auto; flex-shrink:0; }
.cab-btn { border:none; background:transparent; cursor:pointer; width:42px; height:42px; border-radius:12px;
  display:flex; align-items:center; justify-content:center; color:#f5f3fa; font-size:18px; line-height:1; }
.cab-btn:active { transform:scale(0.9); }
.cab-btn.solid { background:rgba(245,243,250,0.12); }

/* options drawer */
.drawer { position:fixed; bottom:0; left:50%; transform:translateX(-50%); width:100%; max-width:480px;
  max-height:90vh; overflow-y:auto; overscroll-behavior:contain; z-index:71; background:var(--card-bg); border-radius:24px 24px 0 0;
  box-shadow:0 -12px 40px rgba(59,54,81,0.3); padding:12px 12px calc(20px + env(safe-area-inset-bottom)) 12px;
  animation:poet-sheet-up 0.26s cubic-bezier(0.2,0.9,0.3,1); }
.drawer-head { display:flex; align-items:center; gap:13px; padding:0 10px 14px 10px;
  border-bottom:1px solid var(--divider); margin-bottom:8px; }
.drawer-head-art { width:48px; height:48px; border-radius:12px; flex-shrink:0; overflow:hidden;
  display:flex; align-items:center; justify-content:center; font-size:12px; font-weight:600; color:rgba(59,54,81,0.55); }
.drawer-head-art img { width:100%; height:100%; object-fit:cover; }
.dsec { font-size:11px; font-weight:700; letter-spacing:0.06em; text-transform:uppercase; color:var(--faint); padding:12px 14px 6px 14px; }
.ditem { display:flex; align-items:center; gap:14px; width:100%; border:none; background:transparent; cursor:pointer;
  text-align:left; padding:11px 14px; border-radius:12px; transition:background 0.15s; }
.ditem:active { transform:scale(0.99); }
.ditem:hover { background: var(--accent-faint); }
.dicon { width:34px; height:34px; border-radius:10px; background:var(--accent-faint); flex-shrink:0;
  display:flex; align-items:center; justify-content:center; }
.dlabel { font-size:14px; font-weight:600; }
.dlabel-sub { font-size:11px; color:var(--muted); }
.dchev { color:var(--faint); font-size:18px; margin-left:auto; }
.ditem-danger .dicon { background:rgba(220,110,120,0.14); }
.ditem-danger .dlabel { color:#c25f6e; }
.ditem-danger:hover { background:rgba(220,110,120,0.1); }

/* drawer sub-sheets */
.sheet-btn { border:none; cursor:pointer; width:100%; padding:14px; border-radius:14px; background:var(--accent); color:#3b3651;
  font-size:14px; font-weight:700; box-shadow:0 3px 12px var(--accent-shadow); margin-top:14px; }
.sheet-btn:active { transform:scale(0.98); }
.pl-create { display:flex; gap:8px; margin-bottom:10px; }
.pl-create input { flex:1; min-width:0; border:1.5px dashed var(--card-border-soft); border-radius:13px; padding:12px 14px;
  font-family:inherit; font-size:14px; background:transparent; color:var(--ink); }
.pl-create input:focus { outline:none; border-color:var(--accent); }
.pl-create button { border:none; cursor:pointer; padding:0 18px; border-radius:13px; background:var(--accent); color:#3b3651; font-weight:700; }
.pl-pick { display:flex; align-items:center; gap:12px; width:100%; border:none; cursor:pointer; text-align:left;
  padding:10px 12px; border-radius:12px; background:transparent; }
.pl-pick:active { transform:scale(0.99); }
.pl-pick.on { background: var(--accent-faint); }
.pl-check { width:22px; height:22px; border-radius:7px; flex-shrink:0; border:2px solid var(--card-border-strong);
  display:flex; align-items:center; justify-content:center; }
.pl-check svg { display:none; }
.pl-pick.on .pl-check { border-color:var(--ink); background:var(--accent); }
.pl-pick.on .pl-check svg { display:block; }
.pl-art { width:40px; height:40px; border-radius:9px; flex-shrink:0; display:flex; align-items:center; justify-content:center;
  font-size:15px; color:rgba(59,54,81,0.5); }
.setas-opt { display:flex; align-items:center; gap:12px; width:100%; border:1.5px solid var(--card-border); cursor:pointer;
  text-align:left; padding:14px; border-radius:13px; background:var(--card-bg); font-size:14px; font-weight:700; }
.setas-opt:active { transform:scale(0.98); }
.setas-opt:hover { background:var(--accent-faint); border-color:var(--accent); }

/* centered sub-sheet modals (song info, delete confirm) */
.center-shield { position:fixed; inset:0; z-index:72; background:rgba(59,54,81,0.4); backdrop-filter:blur(4px);
  display:flex; align-items:center; justify-content:center; padding:28px; animation:poet-fade-in 0.15s ease; touch-action:none; }
.info-card { width:100%; max-width:360px; background:var(--card-bg); border-radius:18px; padding:22px;
  box-shadow:0 20px 60px rgba(59,54,81,0.35); box-sizing:border-box; }
.info-row { display:flex; justify-content:space-between; gap:16px; }
.info-k { font-size:12px; color:var(--muted); flex-shrink:0; }
.info-v { font-size:12px; font-weight:600; color:var(--ink); text-align:right; font-family:monospace; word-break:break-all; }
.btn-delete { border:none; cursor:pointer; padding:11px 22px; border-radius:12px; font-size:13px; font-weight:700;
  letter-spacing:0.04em; background:#c25f6e; color:#ffffff; box-shadow:0 2px 8px rgba(194,95,110,0.4); }
.btn-delete:active { transform:scale(0.95); }

/* context menu */
.menu { position:absolute; right:8px; top:52px; z-index:30; background:var(--menu-glass); backdrop-filter:blur(12px); border:1px solid var(--card-border); border-radius:12px; box-shadow:0 8px 24px rgba(59,54,81,0.18); overflow:hidden; min-width:180px; animation: poet-fade 0.14s ease-out; }
/* full-screen catcher behind an open menu: blocks click-through to the layout below */
.menu-shield { position:fixed; inset:0; z-index:29; background:transparent; }
/* lift the row that owns the open menu above sibling rows and the tray */
.row.menu-open { z-index:45; }
.row.menu-open:active { transform:none; }
.menu button { display:block; width:100%; text-align:left; border:none; background:transparent; cursor:pointer; font-size:13px; font-weight:500; padding:11px 16px; color:var(--ink); }
.menu button:active { background: var(--accent-faint); }
.menu .menu-form { display:flex; gap:6px; padding:8px 10px; }
.menu .menu-form input { flex:1; min-width:0; border:1.5px solid var(--card-border-soft); border-radius:8px; padding:6px 8px; font-family:inherit; font-size:13px; }
.menu .menu-form button { width:auto; padding:6px 10px; border-radius:8px; background:var(--accent); color:#3b3651; font-weight:700; }

/* album grid */
.grid { display:grid; grid-template-columns:1fr 1fr; gap:14px; }
.album-card { cursor:pointer; transition: transform 0.1s; }
.album-card:active { transform: scale(0.97); }
.album-art { aspect-ratio:1; border-radius:16px; display:flex; align-items:center; justify-content:center; font-size:12px; font-weight:600; color:rgba(59,54,81,0.55); overflow:hidden; }
.album-art img { width:100%; height:100%; object-fit:cover; }

/* now playing */
.np-art { width:min(78vw,320px); aspect-ratio:1; border-radius:24px; box-shadow:0 16px 40px var(--accent-shadow); display:flex; align-items:center; justify-content:center; margin:0 auto 24px auto; overflow:hidden; }
.np-art img { width:100%; height:100%; object-fit:cover; }
.np-dot { border:none; cursor:pointer; width:44px; height:44px; border-radius:50%; background:var(--overlay-neutral); font-size:18px; display:flex; align-items:center; justify-content:center; color:var(--ink); }
.np-dot:active { transform: scale(0.9); }
.np-dot.on { background: var(--accent-soft); }
.np-side { border:none; cursor:pointer; width:52px; height:52px; border-radius:50%; background:var(--card-bg); box-shadow:0 2px 8px rgba(59,54,81,0.12); display:flex; align-items:center; justify-content:center; }
.np-side:active { transform: scale(0.9); }
.np-main { border:none; cursor:pointer; width:76px; height:76px; border-radius:50%; background:var(--accent); box-shadow:0 8px 20px var(--accent-shadow); display:flex; align-items:center; justify-content:center; transition: transform 0.12s; }
.np-main:active { transform: scale(0.92); }
.chip { border:none; cursor:pointer; padding:9px 16px; border-radius:99px; background:var(--overlay-neutral); font-size:13px; font-weight:600; color:var(--ink); }
.chip:active { transform: scale(0.95); }
.chip.on { background: var(--accent-soft); }
input.seek { -webkit-appearance:none; appearance:none; width:100%; height:5px; border-radius:3px; outline:none; border:none; cursor:pointer; }
input.seek::-webkit-slider-thumb { -webkit-appearance:none; appearance:none; width:18px; height:18px; border-radius:50%; background:var(--ink); border:3px solid var(--card-bg); box-shadow:0 1px 4px rgba(59,54,81,0.35); }
/* position:relative so each .lyric's offsetTop is deck-relative (auto-scroll math) */
.lyrics-deck { position:relative; width:100%; max-width:340px; margin:16px auto 0 auto; background:var(--lyrics-glass); backdrop-filter:blur(8px); border-radius:16px; padding:18px 20px; display:flex; flex-direction:column; gap:10px; max-height:38vh; overflow-y:auto; overscroll-behavior:contain; }
.lyric { font-size:13px; font-weight:500; color:var(--lyric-dim); transition: all 0.3s; }
.lyric.active { font-size:16px; font-weight:700; color:var(--ink); }

/* settings */
.folder-row { display:flex; align-items:center; gap:10px; padding:10px 12px; border-radius:12px; background:var(--accent-faint); }
.folder-path { flex:1; font-size:13px; font-family:monospace; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.swatch { width:40px; height:40px; border-radius:50%; cursor:pointer; border:3px solid var(--card-bg); transition: transform 0.12s; }
.swatch:active { transform: scale(0.9); }
.swatch.on { border-color: var(--ink); }
.tint-pill { border:1.5px solid var(--card-border-soft); cursor:pointer; padding:9px 16px; border-radius:99px; font-size:13px; font-weight:600; color:var(--ink); }
.tint-pill.on { border-color: var(--ink); }
.spinner { display:inline-block; width:15px; height:15px; border:2.5px solid var(--accent); border-top-color:transparent; border-radius:50%; animation:poet-spin 0.8s linear infinite; }

/* bottom sheets (sort drawer, tag editor) */
.sheet-shield { position:fixed; inset:0; z-index:70; background:rgba(59,54,81,0.35); backdrop-filter:blur(2px); animation:poet-fade-in 0.2s ease; touch-action:none; }
.sheet { position:fixed; bottom:0; left:50%; transform:translateX(-50%); width:100%; max-width:480px; z-index:71;
  background:var(--card-bg); border-radius:24px 24px 0 0; box-shadow:0 -12px 40px rgba(59,54,81,0.25); margin:0;
  padding:12px 20px calc(24px + env(safe-area-inset-bottom)) 20px; animation:poet-sheet-up 0.25s cubic-bezier(0.2,0.9,0.3,1); }
.sheet-tall { max-height:88vh; overflow-y:auto; overscroll-behavior:contain; }
.sheet-grab { width:40px; height:4px; border-radius:2px; background:var(--grabber); margin:0 auto 14px auto; }
.sheet-title { font-size:16px; font-weight:700; }
.sheet-sub { font-size:12px; color:var(--muted); font-family:monospace; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.sheet-head { position:sticky; top:0; z-index:2; background:var(--card-bg); display:flex; align-items:center; justify-content:space-between; gap:12px; padding-bottom:14px; }

/* sort drawer */
.sortbtn { border:none; cursor:pointer; display:inline-flex; align-items:center; gap:7px; padding:10px 14px; border-radius:12px;
  background:var(--overlay-neutral); color:var(--ink); font-size:13px; font-weight:700; flex-shrink:0; }
.sortbtn:active { transform: scale(0.95); }
.sortopt { display:flex; align-items:center; gap:14px; width:100%; border:none; cursor:pointer; text-align:left;
  padding:13px 14px; border-radius:14px; background:var(--overlay-faint); color:var(--ink); transition:background 0.15s; }
.sortopt:active { transform: scale(0.98); }
.sortopt.active { background: var(--accent-faint); }
.radio { width:20px; height:20px; border-radius:50%; border:2px solid var(--ink); box-sizing:border-box;
  display:flex; align-items:center; justify-content:center; flex-shrink:0; }
.radio-dot { width:10px; height:10px; border-radius:50%; background:var(--ink); animation:poet-pop 0.25s ease; }

/* confirmation card */
.confirm-card { width:100%; max-width:340px; background:var(--card-bg); border-radius:18px; padding:24px 22px 16px 22px;
  box-shadow:0 20px 60px rgba(59,54,81,0.35); animation:poet-fade 0.15s ease-out; }
.confirm-title { font-size:16px; font-weight:700; margin-bottom:8px; }
.confirm-msg { font-size:14px; line-height:1.5; color:var(--muted); }
.confirm-strong { color:var(--ink); font-weight:600; font-family:monospace; font-size:13px; word-break:break-all; }
.confirm-actions { display:flex; justify-content:flex-end; gap:8px; margin-top:20px; }
.btn-ghost { border:none; background:transparent; cursor:pointer; padding:11px 18px; border-radius:12px;
  font-size:13px; font-weight:700; letter-spacing:0.04em; color:var(--muted); }
.btn-ghost:active { transform: scale(0.95); background:var(--overlay-neutral); }
.confirm-ok { letter-spacing:0.04em; font-size:13px; padding:11px 22px; }

/* tag editor fields */
.field { display:flex; flex-direction:column; gap:6px; }
.field span { font-size:12px; font-weight:700; color:var(--muted); letter-spacing:0.02em; }
.field input { font-family:inherit; font-size:14px; font-weight:600; color:var(--ink); padding:12px 14px; border-radius:12px;
  border:1.5px solid var(--card-border); background:var(--input-bg); outline:none; transition:border-color 0.15s, box-shadow 0.15s; }
.field input:focus { border-color:var(--accent); box-shadow:0 0 0 3px var(--accent-faint); }

/* full-height tag editor sheet */
.editor-sheet { position:fixed; bottom:0; left:50%; transform:translateX(-50%); width:100%; max-width:480px; height:92vh;
  z-index:71; background:var(--card-bg); border-radius:24px 24px 0 0; box-shadow:0 -12px 40px rgba(59,54,81,0.3);
  display:flex; flex-direction:column; animation:poet-sheet-up 0.28s cubic-bezier(0.2,0.9,0.3,1); }
.ed-head { display:flex; align-items:center; gap:13px; padding:0 20px 14px 20px; flex-shrink:0; }
.ed-head-art { width:46px; height:46px; border-radius:11px; background:var(--accent-faint); flex-shrink:0; overflow:hidden;
  display:flex; align-items:center; justify-content:center; font-size:11px; font-weight:600; color:rgba(59,54,81,0.55); }
.ed-head-art img { width:100%; height:100%; object-fit:cover; }
.ed-head-title { font-size:16px; font-weight:700; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.ed-head-file { font-size:12px; color:var(--muted); font-family:monospace; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.ed-close { border:none; background:var(--overlay-neutral); cursor:pointer; width:34px; height:34px; border-radius:10px;
  display:flex; align-items:center; justify-content:center; color:var(--muted); flex-shrink:0; }
.ed-close:active { transform:scale(0.9); }
.ed-tabs { display:flex; gap:6px; padding:0 20px 14px 20px; flex-shrink:0; }
.ed-tab { flex:1; border:none; cursor:pointer; font-size:13px; font-weight:700; padding:10px 0; border-radius:12px;
  background:var(--overlay-neutral); color:var(--muted); transition:background 0.15s; }
.ed-tab:active { transform:scale(0.97); }
.ed-tab.active { background:var(--accent); color:#3b3651; }
.ed-body { flex:1; overflow-y:auto; overscroll-behavior:contain; padding:4px 20px 20px 20px; min-height:0; }
.ed-field { display:flex; flex-direction:column; gap:6px; }
.ed-field span { font-size:12px; font-weight:700; color:var(--muted); letter-spacing:0.02em; }
.ed-field input, .ed-field textarea { font-family:inherit; font-size:14px; font-weight:600; color:var(--ink); padding:12px 14px;
  border-radius:12px; border:1.5px solid var(--card-border); background:var(--input-bg); outline:none; resize:none;
  transition:border-color 0.15s, box-shadow 0.15s; width:100%; box-sizing:border-box; }
.ed-field textarea { font-weight:500; }
.ed-field input:focus, .ed-field textarea:focus { border-color:var(--accent); box-shadow:0 0 0 3px var(--accent-faint); }

/* rename card + switch */
.ed-rename-card { background:var(--bg); border-radius:14px; padding:14px; }
.ed-switch { border:none; cursor:pointer; width:46px; height:27px; border-radius:99px; background:var(--overlay-strong);
  position:relative; flex-shrink:0; transition:background 0.2s; }
.ed-switch.on { background:var(--accent); }
.ed-knob { position:absolute; top:3px; left:3px; width:21px; height:21px; border-radius:50%; background:#ffffff;
  box-shadow:0 1px 3px rgba(59,54,81,0.3); transition:left 0.2s; }
.ed-switch.on .ed-knob { left:22px; }
.ed-mono-input { width:100%; box-sizing:border-box; font-family:monospace; font-size:13px; font-weight:600; color:var(--ink);
  padding:10px 12px; border-radius:10px; border:1.5px solid var(--card-border); background:var(--input-bg); outline:none; }
.ed-mono-input:focus { border-color:var(--accent); box-shadow:0 0 0 3px var(--accent-faint); }
.ed-rename-preview { font-size:12px; font-family:monospace; font-weight:700; color:var(--muted); margin-top:2px; word-break:break-all; }

/* artwork tab */
.ed-art-tile { width:100%; aspect-ratio:1; max-width:300px; border-radius:20px; overflow:hidden; box-shadow:0 8px 24px var(--accent-shadow);
  display:flex; align-items:center; justify-content:center; background:var(--overlay-neutral); }
.ed-art-tile img { width:100%; height:100%; object-fit:cover; }
.ed-art-empty { text-align:center; color:var(--muted); }
.ed-art-empty .g { font-size:34px; opacity:0.5; }
.ed-art-btn { flex:1; border:1.5px solid var(--card-border-soft); cursor:pointer; font-family:inherit; padding:12px; border-radius:13px;
  background:var(--card-bg); color:var(--ink); font-size:13px; font-weight:700; }
.ed-art-btn:active { transform:scale(0.97); }

/* lyrics tab */
.ed-seg { display:flex; gap:6px; padding:4px; background:var(--bg); border-radius:12px; }
.ed-seg-btn { flex:1; border:none; cursor:pointer; font-family:inherit; font-size:13px; font-weight:700; padding:9px 0; border-radius:9px;
  background:transparent; color:var(--muted); }
.ed-seg-btn.active { background:var(--card-bg); color:var(--ink); box-shadow:0 1px 4px rgba(59,54,81,0.12); }
.ed-lrc-transport { display:flex; align-items:center; gap:12px; padding:12px 14px; border-radius:14px; background:var(--accent-faint); margin-bottom:12px; }
.ed-lrc-play { border:none; cursor:pointer; width:44px; height:44px; border-radius:50%; background:var(--accent); flex-shrink:0;
  display:flex; align-items:center; justify-content:center; box-shadow:0 3px 10px var(--accent-shadow); }
.ed-lrc-play:active { transform:scale(0.9); }
.ed-lrc-clock { font-size:20px; font-weight:700; font-variant-numeric:tabular-nums; letter-spacing:0.02em; }
.ed-lrc-count { font-size:11px; color:var(--muted); }
.ed-lrc-reset { border:none; background:transparent; cursor:pointer; font-family:inherit; font-size:12px; font-weight:700;
  color:var(--muted); padding:6px 8px; border-radius:8px; flex-shrink:0; }
.ed-lrc-reset:active { transform:scale(0.95); background:var(--overlay-neutral); }
.ed-lrc-stamp { border:none; cursor:pointer; font-family:inherit; width:100%; padding:14px; border-radius:14px; background:var(--panel-strong);
  color:var(--panel-strong-text); font-size:14px; font-weight:700; box-shadow:0 4px 14px rgba(59,54,81,0.28); margin-bottom:12px; }
.ed-lrc-stamp:active { transform:scale(0.98); }
.ed-lrc-rows { display:flex; flex-direction:column; gap:3px; margin-bottom:12px; }
.ed-lrc-row { display:flex; align-items:center; gap:12px; padding:10px 12px; border-radius:12px; cursor:pointer; background:transparent; transition:background 0.15s; }
.ed-lrc-row.next { background:var(--overlay-faint); }
.ed-lrc-row.active { background:var(--accent-faint); }
.ed-lrc-row .stamp { font-size:12px; font-family:monospace; font-weight:700; color:var(--faint); flex-shrink:0; width:78px; }
.ed-lrc-row.stamped .stamp { color:var(--muted); }
.ed-lrc-row .txt { font-size:14px; font-weight:500; color:var(--muted); flex:1; }
.ed-lrc-row.stamped .txt { color:var(--ink); }
.ed-lrc-row.active .txt { font-weight:700; }
.ed-lrc-badge { font-size:10px; font-weight:700; color:#3b3651; background:var(--accent); padding:3px 7px; border-radius:99px; flex-shrink:0; }
.ed-lrc-empty { font-size:13px; color:var(--muted); text-align:center; padding:20px; }
.ed-lrc-export { border:1.5px solid var(--accent); cursor:pointer; font-family:inherit; width:100%; padding:12px; border-radius:13px;
  background:var(--card-bg); color:var(--ink); font-size:13px; font-weight:700; }
.ed-lrc-export:active { transform:scale(0.98); }
.ed-savebar { flex-shrink:0; padding:12px 20px calc(16px + env(safe-area-inset-bottom)) 20px; border-top:1px solid var(--divider); background:var(--card-bg); }

/* appearance (light / dark) segmented control in Settings */
.theme-opt { flex:1; display:flex; align-items:center; justify-content:center; gap:8px; border:1.5px solid var(--card-border-soft);
  cursor:pointer; font-family:inherit; padding:12px 8px; border-radius:12px; background:var(--input-bg); color:var(--ink);
  font-size:14px; font-weight:700; transition:border-color 0.15s, background 0.15s; }
.theme-opt:active { transform: scale(0.97); }
.theme-opt.on { border-color:var(--accent); background:var(--accent-faint); }

/* onboarding — no backdrop-filter here: blur on a full-screen fixed shield
   glitches Android WebView compositing (elements behind it vanish) */
#tip-shield { position:fixed; inset:0; z-index:40; background:rgba(59,54,81,0.35); }
.tip-banner { position:absolute; left:24px; right:24px; top:100%; margin-top:14px; z-index:60; animation:poet-bob 2.2s ease-in-out infinite; }
.tip-arrow { position:absolute; top:-7px; left:50%; width:14px; height:14px; background:var(--panel-strong); transform:translateX(-50%) rotate(45deg); }
.tip-body { background:var(--panel-strong); color:var(--panel-strong-text); border-radius:14px; padding:16px 18px; box-shadow:0 12px 32px rgba(59,54,81,0.35); }

/* tray */
#tray { position:fixed; bottom:0; left:50%; transform:translateX(-50%); width:100%; max-width:480px; z-index:35; background:var(--card-glass); backdrop-filter:blur(16px); border-top:1px solid var(--divider); padding:10px 16px calc(10px + env(safe-area-inset-bottom)) 16px; }
#tray-progress { position:absolute; top:0; left:0; height:3px; background:var(--accent); width:0%; transition:width 0.4s linear; }
.tray-inner { display:flex; align-items:center; gap:12px; }
.tray-info { display:flex; align-items:center; gap:12px; flex:1; min-width:0; cursor:pointer; }
.tray-art { width:42px; height:42px; border-radius:10px; flex-shrink:0; display:flex; align-items:center; justify-content:center; font-size:11px; font-weight:600; color:rgba(59,54,81,0.55); overflow:hidden; background:var(--accent-faint); }
.tray-art img { width:100%; height:100%; object-fit:cover; }
.tray-btn { border:none; background:transparent; cursor:pointer; width:40px; height:40px; display:flex; align-items:center; justify-content:center; border-radius:50%; }
.tray-btn:active { transform: scale(0.9); }
.tray-play { border:none; cursor:pointer; width:48px; height:48px; border-radius:50%; background:var(--accent); display:flex; align-items:center; justify-content:center; box-shadow:0 3px 10px var(--accent-shadow); }
.tray-play:active { transform: scale(0.9); }

/* queue panel */
@keyframes queue-up { from { transform:translate(-50%,100%); } to { transform:translate(-50%,0); } }
@keyframes queue-eq { 0%,100% { height:5px; } 50% { height:15px; } }
.queue-shield { position:fixed; inset:0; z-index:64; background:rgba(59,54,81,0.35); backdrop-filter:blur(2px); touch-action:none; }
.queue-panel { position:fixed; bottom:0; left:50%; transform:translateX(-50%); width:100%; max-width:480px; max-height:86vh; z-index:65;
  background:var(--bg); border-radius:22px 22px 0 0; box-shadow:0 -8px 32px rgba(59,54,81,0.25);
  padding:16px 16px calc(14px + env(safe-area-inset-bottom)) 16px; display:flex; flex-direction:column; animation: queue-up 0.22s ease-out; }
#qp-body { overflow-y:auto; overscroll-behavior:contain; min-height:0; padding:2px 0 4px 0; }
.qp-hdr { display:flex; align-items:center; gap:12px; padding:0 2px 10px 2px; flex-shrink:0; }
.qp-back { border:none; cursor:pointer; width:38px; height:38px; border-radius:12px; background:var(--overlay-neutral);
  display:flex; align-items:center; justify-content:center; flex-shrink:0; }
.qp-back:active { transform:scale(0.92); }
.qp-title { font-size:19px; font-weight:700; letter-spacing:-0.02em; }
.qp-src { font-size:12px; color:var(--muted); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.qp-clear { border:none; background:transparent; cursor:pointer; font-size:13px; font-weight:600; color:var(--muted); padding:8px 10px; border-radius:10px; flex-shrink:0; }
.qp-clear:active { transform:scale(0.95); background:var(--overlay-neutral); }
.qp-label { display:flex; align-items:center; justify-content:space-between; font-size:12px; font-weight:700; letter-spacing:0.06em;
  text-transform:uppercase; color:var(--muted); margin:8px 6px 10px 6px; }
.qp-meta { font-size:12px; font-weight:400; color:var(--muted); text-transform:none; letter-spacing:0; }
.qp-now { display:flex; align-items:center; gap:13px; padding:12px 14px; border-radius:18px; background:var(--accent-faint); box-shadow:0 4px 16px var(--accent-shadow); }
.qp-eq { display:flex; align-items:flex-end; gap:3px; height:16px; margin-right:2px; flex-shrink:0; }
.qp-eq span { width:3px; background:var(--ink); border-radius:2px; animation:queue-eq 0.9s ease-in-out infinite; }
.qp-eq span:nth-child(2) { animation-delay:0.25s; }
.qp-eq span:nth-child(3) { animation-delay:0.5s; }
.qp-eq.off { visibility:hidden; }
.qp-playbtn { border:none; cursor:pointer; width:42px; height:42px; border-radius:50%; background:var(--accent);
  display:flex; align-items:center; justify-content:center; box-shadow:0 3px 10px var(--accent-shadow); flex-shrink:0; }
.qp-playbtn:active { transform:scale(0.9); }
#qp-list { display:flex; flex-direction:column; gap:2px; }
.q-row { display:flex; align-items:center; gap:10px; padding:9px 10px 9px 8px; border-radius:14px; animation:poet-fade 0.25s ease; transition:transform 0.1s, background 0.2s; }
.q-row.dragging { position:relative; z-index:5; background:var(--card-bg); box-shadow:0 8px 24px rgba(59,54,81,0.25); }
.q-num { width:20px; text-align:center; font-size:13px; font-weight:700; color:var(--faint); font-variant-numeric:tabular-nums; flex-shrink:0; }
.q-x { border:none; background:transparent; cursor:pointer; width:32px; height:32px; border-radius:8px;
  display:flex; align-items:center; justify-content:center; color:var(--faint); flex-shrink:0; }
.q-x:active { transform:scale(0.9); background:var(--divider); color:var(--muted); }
.q-grab { width:24px; padding:8px 0; display:flex; flex-direction:column; gap:3px; align-items:center; flex-shrink:0; cursor:grab; touch-action:none; }
.q-grab span { width:14px; height:2px; border-radius:2px; background:var(--grabber); }

/* modal + toast */
#modal-root .modal-shield { position:fixed; inset:0; z-index:70; background:rgba(59,54,81,0.35); backdrop-filter:blur(2px); display:flex; align-items:center; justify-content:center; padding:24px; touch-action:none; }
.modal { background:var(--card-bg); border-radius:18px; padding:20px; width:100%; max-width:400px; box-shadow:0 16px 48px rgba(59,54,81,0.3); animation:poet-fade 0.15s ease-out; }
.modal label { display:block; font-size:12px; font-weight:600; color:var(--muted); margin:12px 0 4px 0; }
.modal input { width:100%; border:1.5px solid var(--card-border-soft); border-radius:10px; padding:10px 12px; font-family:inherit; font-size:14px; color:var(--ink); background:var(--input-bg); }
.modal input:focus { outline:none; border-color: var(--accent); }
#toast { position:fixed; bottom:96px; left:50%; transform:translateX(-50%); z-index:90; background:var(--panel-strong); color:var(--panel-strong-text); font-size:13px; font-weight:600; padding:10px 18px; border-radius:99px; box-shadow:0 8px 24px rgba(59,54,81,0.35); opacity:0; pointer-events:none; transition:opacity 0.25s; max-width:85vw; text-align:center; }
#toast.show { opacity:1; }
/* accent variant: minimal pill for Now Playing state changes (shuffle/repeat/sleep) */
#toast.accent { background:var(--accent); color:#3b3651; box-shadow:0 8px 24px var(--accent-shadow); }

/* sleep timer drawer: inline expanding panels (song stepper / custom minutes) */
.sleep-panel { display:flex; align-items:center; gap:10px; margin:2px 14px 10px 62px; padding:10px 12px;
  border-radius:12px; background:var(--overlay-faint); animation:poet-fade 0.15s ease-out; }
.sleep-step { border:1.5px solid var(--card-border-soft); cursor:pointer; width:34px; height:34px; border-radius:10px;
  background:var(--card-bg); color:var(--ink); font-size:17px; font-weight:700; line-height:1; flex-shrink:0; }
.sleep-step:active { transform:scale(0.92); }
.sleep-count { flex:1; text-align:center; font-size:14px; font-weight:700; font-variant-numeric:tabular-nums; }
.sleep-set { border:none; cursor:pointer; padding:9px 18px; border-radius:10px; background:var(--accent); color:#3b3651;
  font-size:13px; font-weight:700; flex-shrink:0; }
.sleep-set:active { transform:scale(0.95); }
.sleep-panel input { width:64px; border:1.5px solid var(--card-border-soft); border-radius:10px; padding:8px 10px;
  font-family:inherit; font-size:14px; font-weight:700; color:var(--ink); background:var(--input-bg); text-align:center; }
.sleep-panel input:focus { outline:none; border-color:var(--accent); }

.empty { text-align:center; color:var(--muted); padding:48px 20px; font-size:14px; }
.searchrow { display:flex; gap:8px; margin-bottom:14px; }
.searchrow input { flex:1; min-width:0; border:none; border-radius:12px; padding:10px 14px; font-family:inherit; font-size:14px; color:var(--ink); background:var(--overlay-neutral); }
.searchrow input:focus { outline:2px solid var(--accent); }
.searchrow select { border:none; border-radius:12px; padding:10px; font-family:inherit; font-size:13px; font-weight:600; color:var(--ink); background:var(--overlay-neutral); }

/* rendered markdown (About screen) */
.md { color:var(--ink); font-size:14px; line-height:1.65; }
.md h1 { font-size:22px; font-weight:700; letter-spacing:-0.02em; margin:0 0 10px 0; }
.md h2 { font-size:16px; font-weight:700; margin:22px 0 8px 0; }
.md h3 { font-size:14px; font-weight:700; margin:16px 0 6px 0; }
.md p { margin:0 0 12px 0; color:var(--muted); }
.md ul, .md ol { margin:0 0 12px 0; padding-left:20px; color:var(--muted); }
.md li { margin:4px 0; }
.md li strong, .md p strong { color:var(--ink); }
.md a { color:var(--link); text-decoration:none; font-weight:600; }
.md a:active { opacity:0.7; }
.md code { font-family:monospace; font-size:12px; background:var(--overlay-neutral); color:var(--ink); padding:2px 6px; border-radius:6px; }
.md pre { background:var(--overlay-neutral); border-radius:12px; padding:14px; overflow-x:auto; margin:0 0 12px 0; }
.md pre code { background:transparent; padding:0; font-size:12px; line-height:1.5; }
.md blockquote { margin:0 0 12px 0; padding:8px 14px; border-left:3px solid var(--accent); background:var(--overlay-faint); border-radius:0 10px 10px 0; color:var(--muted); }
.md hr { border:none; border-top:1px solid var(--divider); margin:18px 0; }
</style>
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
   Icons are interpolated from Views so the poller and the server render
   the exact same markup. */
var ICON_SHUFFLE = ['${Views.shuffleIcon(0)}', '${Views.shuffleIcon(1)}'];
var SHUFFLE_TITLES = ['${Views.shuffleTitle(0)}', '${Views.shuffleTitle(1)}'];
/* repeat button, indexed by mode: 1 repeat one, 2 repeat playlist, 3 play
   single & stop; slot 0 falls back to repeat playlist for stale state. */
var ICON_REPEAT = ['${Views.repeatIcon(2)}', '${Views.repeatIcon(1)}', '${Views.repeatIcon(2)}', '${Views.repeatIcon(3)}'];
var REPEAT_TITLES = ['${Views.repeatTitle(2)}', '${Views.repeatTitle(1)}', '${Views.repeatTitle(2)}', '${Views.repeatTitle(3)}'];

var poetScreenUrl = '/screens/library';
var poetSeeking = false;
var poetShownTrack = -1;
var poetTrayTrack = -2;
var poetTrayMod = -1;
var poetLastState = null;
var poetQueueDrag = false;

function fmt(ms) {
  var s = Math.floor(ms / 1000), m = Math.floor(s / 60); s = s % 60;
  return m + ':' + (s < 10 ? '0' + s : s);
}

function poetGo(url) { htmx.ajax('GET', url, { target: '#main-container', swap: 'innerHTML' }); }

function poetToast(msg, accent) {
  var t = document.getElementById('toast');
  t.textContent = msg;
  t.classList.toggle('accent', !!accent);
  t.classList.add('show');
  clearTimeout(t._h);
  t._h = setTimeout(function () { t.classList.remove('show'); }, 2200);
}

function closeMenus() {
  document.querySelectorAll('.menu-slot').forEach(function (m) { m.innerHTML = ''; });
  document.querySelectorAll('.row.menu-open').forEach(function (r) { r.classList.remove('menu-open'); });
}

function openQueue() {
  htmx.ajax('GET', '/partial/queue', { target: '#queue-root', swap: 'innerHTML' });
}
function closeQueue() {
  document.getElementById('queue-root').innerHTML = '';
}
function closeSheet() {
  document.getElementById('sheet-root').innerHTML = '';
}

/* freeze the page scroller while any overlay root has content, so drawer
   scrolling can never chain into the library list behind it */
(function () {
  var roots = ['sheet-root', 'queue-root', 'modal-root'].map(function (id) {
    return document.getElementById(id);
  });
  function syncOverlayLock() {
    var open = roots.some(function (r) { return r.firstChild !== null; });
    document.body.classList.toggle('overlay-open', open);
  }
  var mo = new MutationObserver(syncOverlayLock);
  roots.forEach(function (r) { mo.observe(r, { childList: true }); });
})();

/* keep an opened context menu fully on screen: flip upward near the bottom edge */
function positionMenu(menu) {
  menu.style.top = ''; menu.style.bottom = '';
  var r = menu.getBoundingClientRect();
  var limit = window.innerHeight - 78; /* keep clear of the media tray */
  if (r.bottom > limit) {
    menu.style.top = 'auto';
    menu.style.bottom = '52px';
    var r2 = menu.getBoundingClientRect();
    if (r2.top < 8) {
      menu.style.bottom = 'auto';
      menu.style.top = Math.max(8 - r.top + 52, 52 - (r.bottom - limit)) + 'px';
    }
  }
}

document.addEventListener('click', function (e) {
  if (!e.target.closest('.menu') && !e.target.closest('.row-menu-btn')) closeMenus();
});

document.body.addEventListener('poet-toast', function (e) { poetToast(e.detail.value || e.detail); });
document.body.addEventListener('poet-toast-accent', function (e) { poetToast(e.detail.value || e.detail, true); });
document.body.addEventListener('poet-refresh', function () { closeMenus(); poetGo(poetScreenUrl); });
document.body.addEventListener('poet-close-modal', function () {
  if (window.poetEd && poetEd.tick) clearInterval(poetEd.tick);
  window.poetEd = null;
  document.getElementById('modal-root').innerHTML = '';
});
document.body.addEventListener('poet-goto', function (e) { closeMenus(); poetGo(e.detail.value || e.detail); });
document.body.addEventListener('poet-art-changed', function (e) {
  /* Bust any cached cover for this track so freshly written art shows at once. */
  var id = e.detail.value || e.detail;
  document.querySelectorAll('img[src*="/api/art/' + id + '"]').forEach(function (img) {
    img.src = '/api/art/' + id + '?v=' + Date.now();
  });
});

document.body.addEventListener('htmx:afterSwap', function (e) {
  var t = e.detail.target;
  if (t && t.classList && t.classList.contains('menu-slot')) {
    var row = t.closest('.row');
    if (row) row.classList.add('menu-open');
    var menu = t.querySelector('.menu');
    if (menu) positionMenu(menu);
  }
  /* re-paint selection after the song list is filtered or re-sorted in place */
  if (t && t.id === 'song-list' && poetInSelect()) poetRenderSel();
  if (e.detail.target && e.detail.target.id === 'main-container') {
    /* a fresh screen invalidates any lingering selection */
    if (poetInSelect()) poetExitSelect();
    var path = e.detail.pathInfo && (e.detail.pathInfo.finalRequestPath || e.detail.pathInfo.requestPath);
    if (path && path.indexOf('/screens/') === 0) poetScreenUrl = path;
    var lib = document.getElementById('nav-library'), set = document.getElementById('nav-settings');
    lib.classList.toggle('active', poetScreenUrl.indexOf('/screens/settings') !== 0);
    set.classList.toggle('active', poetScreenUrl.indexOf('/screens/settings') === 0);
    /* Sync the shown-track marker with what was actually rendered. Resetting
       it to -1 here made every poll re-fetch the whole Now Playing screen
       once a second — a permanent full-screen flicker. */
    var npRoot = document.getElementById('np-root');
    poetShownTrack = npRoot ? Number(npRoot.getAttribute('data-track-id')) : -1;
    window.scrollTo(0, 0);
  }
});

/* ---- multi-select + options drawer ----
   Tap a row plays it; long-press (or right-click) enters multi-select, where
   tapping toggles the checkbox and a Contextual Action Bar appears; the ⋯
   button opens the full options drawer. Selection lives on the client and is
   handed to the server as a comma-separated id list on each action. */
var poetSel = [];
function poetInSelect() { return document.body.classList.contains('select-mode'); }
function poetRowsAll() { return document.querySelectorAll('#main-container .row[data-track-id]'); }

function poetRenderSel() {
  document.querySelectorAll('.row[data-track-id]').forEach(function (r) {
    r.classList.toggle('selected', poetSel.indexOf(Number(r.getAttribute('data-track-id'))) >= 0);
  });
  var c = document.getElementById('cab-count');
  if (c) c.textContent = poetSel.length + ' selected';
  var all = document.getElementById('cab-all');
  if (all) {
    var total = poetRowsAll().length;
    all.textContent = (total > 0 && poetSel.length >= total) ? 'Clear all' : 'Select all';
  }
}
function poetEnterSelect(id) {
  document.body.classList.add('select-mode');
  if (poetSel.indexOf(id) < 0) poetSel.push(id);
  poetRenderSel();
}
function poetToggleSel(id) {
  var i = poetSel.indexOf(id);
  if (i >= 0) poetSel.splice(i, 1); else poetSel.push(id);
  if (poetSel.length === 0) poetExitSelect(); else poetRenderSel();
}
function poetExitSelect() {
  poetSel = [];
  document.body.classList.remove('select-mode');
  document.querySelectorAll('.row.selected').forEach(function (r) { r.classList.remove('selected'); });
}
function poetSelectAll() {
  var rows = poetRowsAll();
  if (poetSel.length >= rows.length) { poetExitSelect(); return; }
  poetSel = Array.prototype.map.call(rows, function (r) { return Number(r.getAttribute('data-track-id')); });
  poetRenderSel();
}
function poetSelIds() { return poetSel.join(','); }
function poetSelCq() {
  var r = document.querySelector('.row.selected[data-cq]') || document.querySelector('.row[data-cq]');
  return r ? r.getAttribute('data-cq') : 'ctx=songs';
}

function poetOpenDrawer(url) { htmx.ajax('GET', url, { target: '#sheet-root', swap: 'innerHTML' }); }
function poetDrawerClose() { closeSheet(); }
function poetSubBack(ids, cq) { poetOpenDrawer('/api/library/drawer?ids=' + ids + '&' + cq); }
/* close every drawer/sub-sheet and leave selection mode — used after an action commits */
function poetFinish() { closeSheet(); poetExitSelect(); }

/* Contextual Action Bar batch actions (read the live selection) */
function poetBatch(kind) {
  if (!poetSel.length) return;
  htmx.ajax('POST', '/api/tracks/' + kind + '?ids=' + poetSelIds() + '&' + poetSelCq(), { swap: 'none' });
  poetExitSelect();
}
function poetBatchSheet(kind) {
  if (!poetSel.length) return;
  poetOpenDrawer('/api/library/sub?kind=' + kind + '&ids=' + poetSelIds() + '&' + poetSelCq());
}
function poetBatchDrawer() {
  if (!poetSel.length) return;
  poetOpenDrawer('/api/library/drawer?ids=' + poetSelIds() + '&' + poetSelCq());
}

/* add-to-playlist sub-sheet: collect the checked playlists */
function poetPlToggle(el) { el.classList.toggle('on'); }
function poetAddPlaylists(ids) {
  var pids = [];
  document.querySelectorAll('.pl-pick.on[data-pid]').forEach(function (el) { pids.push(el.getAttribute('data-pid')); });
  if (!pids.length) { poetToast('No playlist selected'); return; }
  htmx.ajax('POST', '/api/tracks/add-playlists?ids=' + ids + '&pids=' + pids.join(','), { swap: 'none' });
  poetFinish();
}

/* sleep timer drawer: expanding panels + commit */
function poetSleepPanel(kind) {
  var s = document.getElementById('sleep-songs-panel'), c = document.getElementById('sleep-custom-panel');
  if (!s || !c) return;
  var open = kind === 'songs' ? s : c, other = kind === 'songs' ? c : s;
  other.hidden = true;
  open.hidden = !open.hidden;
}
function poetSleepAdj(d) {
  var el = document.getElementById('sleep-songs-n');
  if (!el) return;
  el.textContent = Math.min(99, Math.max(1, Number(el.textContent) + d));
}
function poetSleepSetSongs() {
  var el = document.getElementById('sleep-songs-n');
  var n = el ? Number(el.textContent) || 1 : 1;
  htmx.ajax('POST', '/api/player/sleep-songs?n=' + n, { swap: 'none' });
  closeSheet();
}
function poetSleepSetCustom() {
  var v = Number((document.getElementById('sleep-custom-min') || {}).value);
  if (!v || v < 1) { poetToast('Enter the minutes first'); return; }
  htmx.ajax('POST', '/api/player/sleep?min=' + Math.min(600, Math.round(v)), { swap: 'none' });
  closeSheet();
}

/* delegated row interaction: menu button, play, or select-toggle */
document.addEventListener('click', function (e) {
  var menuBtn = e.target.closest('.row-menu-btn');
  if (menuBtn) {
    var mrow = menuBtn.closest('.row[data-drawer-url]');
    if (mrow) { e.stopPropagation(); poetOpenDrawer(mrow.getAttribute('data-drawer-url')); }
    return;
  }
  var row = e.target.closest('.row[data-play-url]');
  if (!row) return;
  var id = Number(row.getAttribute('data-track-id'));
  if (poetInSelect()) { poetToggleSel(id); }
  else { fetch(row.getAttribute('data-play-url'), { method: 'POST' }).catch(function () {}); }
});

/* long-press (touch) and right-click (pointer) enter multi-select */
(function () {
  var timer = null, fired = false, startX = 0, startY = 0;
  document.addEventListener('touchstart', function (e) {
    var row = e.target.closest('.row[data-play-url]');
    if (!row || e.target.closest('.row-menu-btn')) return;
    fired = false;
    startX = e.touches[0].clientX; startY = e.touches[0].clientY;
    timer = setTimeout(function () {
      fired = true;
      if (navigator.vibrate) navigator.vibrate(15);
      poetEnterSelect(Number(row.getAttribute('data-track-id')));
    }, 420);
  }, { passive: true });
  document.addEventListener('touchmove', function (e) {
    if (!timer) return;
    var dx = e.touches[0].clientX - startX, dy = e.touches[0].clientY - startY;
    if (dx * dx + dy * dy > 100) { clearTimeout(timer); timer = null; }
  }, { passive: true });
  document.addEventListener('touchend', function (e) {
    if (timer) { clearTimeout(timer); timer = null; }
    if (fired) { e.preventDefault(); fired = false; }
  }, { passive: false });
  document.addEventListener('touchcancel', function () { if (timer) { clearTimeout(timer); timer = null; } });
})();
document.addEventListener('contextmenu', function (e) {
  var row = e.target.closest('.row[data-play-url]');
  if (row) { e.preventDefault(); poetEnterSelect(Number(row.getAttribute('data-track-id'))); }
});

/* ---- bottom drawers: swipe down to close ----
   When a drawer/sheet in #sheet-root is scrolled to the top, dragging it
   downward moves it with the finger; past the threshold it closes, otherwise
   it springs back. Upward drags fall through to normal drawer scrolling. */
(function () {
  var panel = null, startY = 0, dy = 0, dragging = false;
  document.addEventListener('touchstart', function (e) {
    panel = e.target.closest('#sheet-root .drawer, #sheet-root .sheet');
    if (!panel || e.target.closest('input, textarea')) { panel = null; return; }
    startY = e.touches[0].clientY; dy = 0; dragging = false;
  }, { passive: true });
  document.addEventListener('touchmove', function (e) {
    if (!panel) return;
    dy = e.touches[0].clientY - startY;
    if (!dragging) {
      if (dy > 8 && panel.scrollTop <= 0) { dragging = true; panel.style.transition = 'none'; }
      else if (dy < -8) { panel = null; return; } /* scrolling up: leave it alone */
    }
    if (dragging) {
      panel.style.transform = 'translate(-50%,' + Math.max(0, dy) + 'px)';
      e.preventDefault();
    }
  }, { passive: false });
  function endDrag() {
    if (!panel) return;
    var p = panel; panel = null;
    if (dragging && dy > 90) { closeSheet(); }
    else { p.style.transition = ''; p.style.transform = ''; }
    dragging = false;
  }
  document.addEventListener('touchend', endDrag);
  document.addEventListener('touchcancel', endDrag);
})();

/* ---- queue panel: drag the grab handle to reorder upcoming songs ----
   The handle has touch-action:none, so the browser never scrolls the panel
   during a drag; rows are live-reordered in the DOM and the final position
   is committed to the player with /api/queue/move on release. */
(function () {
  var row = null, list = null, fromQi = -1;
  document.addEventListener('touchstart', function (e) {
    var grab = e.target.closest('.q-grab');
    if (!grab) return;
    row = grab.closest('.q-row');
    list = row.parentElement;
    fromQi = Number(row.getAttribute('data-qi'));
    poetQueueDrag = true;
    row.classList.add('dragging');
  }, { passive: true });
  document.addEventListener('touchmove', function (e) {
    if (!row) return;
    var y = e.touches[0].clientY;
    var next = row.nextElementSibling, prev = row.previousElementSibling;
    if (next && y > next.getBoundingClientRect().top + next.offsetHeight / 2) list.insertBefore(next, row);
    else if (prev && y < prev.getBoundingClientRect().bottom - prev.offsetHeight / 2) list.insertBefore(row, prev);
  }, { passive: true });
  function finishDrag() {
    if (!row) return;
    var dragged = row; row = null; poetQueueDrag = false;
    dragged.classList.remove('dragging');
    var to = Number(list.getAttribute('data-base')) + Array.prototype.indexOf.call(list.children, dragged);
    if (to !== fromQi) {
      htmx.ajax('POST', '/api/queue/move', { target: '#qp-body', swap: 'innerHTML', values: { from: fromQi, to: to } });
    }
  }
  document.addEventListener('touchend', finishDrag);
  document.addEventListener('touchcancel', finishDrag);
})();

/* ---- live playback state poller ---- */
function applyState(s) {
  poetLastState = s;
  /* wall-clock stamp for sub-second position estimation (LRC maker) */
  s._at = Date.now();
  var pct = s.dur > 0 ? (s.pos / s.dur * 100) : 0;
  document.getElementById('tray-progress').style.width = pct + '%';
  document.getElementById('tray-play').innerHTML = s.playing ? ICON_PAUSE_SM : ICON_PLAY_SM;
  if (s.trackId !== poetTrayTrack || s.mod !== poetTrayMod) {
    poetTrayTrack = s.trackId; poetTrayMod = s.mod;
    document.getElementById('tray-title').textContent = s.title;
    document.getElementById('tray-artist').textContent = s.artist;
    var art = document.getElementById('tray-art');
    art.innerHTML = s.trackId >= 0 ? '<img src="/api/art/' + s.trackId + '?v=' + s.mod + '" alt="">' : '♪';
  }
  document.querySelectorAll('.row[data-track-id]').forEach(function (r) {
    r.classList.toggle('playing', Number(r.getAttribute('data-track-id')) === s.trackId);
  });

  /* queue panel: live EQ bars + play button, re-render body when the track changes */
  var qpState = document.getElementById('qp-state');
  if (qpState) {
    var qpPlay = document.getElementById('qp-play');
    if (qpPlay) {
      var qpIcon = s.playing ? ICON_PAUSE_SM : ICON_PLAY_SM;
      if (qpPlay._icon !== qpIcon) { qpPlay._icon = qpIcon; qpPlay.innerHTML = qpIcon; }
    }
    var qpEq = document.getElementById('qp-eq');
    if (qpEq) qpEq.classList.toggle('off', !s.playing);
    if (!poetQueueDrag && Number(qpState.getAttribute('data-track-id')) !== s.trackId) {
      htmx.ajax('GET', '/partial/queue-body', { target: '#qp-body', swap: 'innerHTML' });
    }
  }

  var np = document.getElementById('np-root');
  if (np) {
    if (s.trackId !== poetShownTrack && s.trackId >= 0) {
      poetShownTrack = s.trackId;
      var lyricsOpen = !!document.querySelector('#lyrics-deck .lyric');
      htmx.ajax('GET', '/screens/now-playing' + (lyricsOpen ? '?lyrics=1' : ''), { target: '#main-container', swap: 'innerHTML' });
      return;
    }
    var slider = document.getElementById('np-slider');
    if (slider && !poetSeeking) {
      slider.value = Math.floor(s.pos / 1000);
      slider.max = Math.max(1, Math.floor(s.dur / 1000));
      paintSlider(slider);
      document.getElementById('np-cur').textContent = fmt(s.pos);
      document.getElementById('np-tot').textContent = fmt(s.dur);
    }
    var play = document.getElementById('np-play');
    if (play) play.innerHTML = s.playing ? ICON_PAUSE_LG : ICON_PLAY_LG;
    var sh = document.getElementById('np-shuffle');
    if (sh) {
      sh.classList.toggle('on', s.shuffle !== 0);
      var shIcon = ICON_SHUFFLE[s.shuffle] || ICON_SHUFFLE[0];
      if (sh._icon !== shIcon) { sh._icon = shIcon; sh.innerHTML = shIcon; sh.title = SHUFFLE_TITLES[s.shuffle] || SHUFFLE_TITLES[0]; }
    }
    var rp = document.getElementById('np-repeat');
    if (rp) {
      rp.classList.toggle('on', s.repeat === 1 || s.repeat === 3);
      var rpIcon = ICON_REPEAT[s.repeat] || ICON_REPEAT[0];
      if (rp._icon !== rpIcon) { rp._icon = rpIcon; rp.innerHTML = rpIcon; rp.title = REPEAT_TITLES[s.repeat] || REPEAT_TITLES[0]; }
    }
    var sp = document.getElementById('np-speed');
    if (sp) sp.textContent = s.speed.toFixed(2).replace(/0$/, '').replace(/\.0$/, '.0') + 'x';
    var sl = document.getElementById('np-sleep');
    if (sl) {
      sl.textContent = s.sleep >= 0 ? 'sleep ' + Math.ceil(s.sleep / 60000) + 'm'
        : s.sleepSongs > 0 ? 'sleep ' + s.sleepSongs + '♪' : 'sleep';
      sl.classList.toggle('on', s.sleep >= 0 || s.sleepSongs > 0);
    }
    var fv = document.getElementById('np-fav');
    if (fv) fv.classList.toggle('on', !!s.fav);
    var deck = document.getElementById('lyrics-deck');
    if (deck) {
      var lines = deck.querySelectorAll('.lyric'), active = null;
      lines.forEach(function (l) {
        if (Number(l.getAttribute('data-at')) <= s.pos) active = l;
      });
      lines.forEach(function (l) { l.classList.toggle('active', l === active); });
      /* Centre the active line by scrolling ONLY the deck box, and only when
         the line changes. scrollIntoView walked every scrollable ancestor —
         it yanked the whole page toward the deck once per second. */
      var box = deck.querySelector('.lyrics-deck');
      if (box && active && !poetSeeking && box._active !== active) {
        box._active = active;
        box.scrollTo({ top: active.offsetTop - (box.clientHeight - active.offsetHeight) / 2, behavior: 'smooth' });
      }
    }
  }
}

function poetPoll() {
  fetch('/api/player/state').then(function (r) { return r.json(); }).then(applyState).catch(function () {});
}
setInterval(poetPoll, 1000);

function paintSlider(el) {
  var pct = el.max > 0 ? (el.value / el.max * 100) : 0;
  el.style.background = 'linear-gradient(to right, var(--accent) ' + pct + '%, var(--track-empty) ' + pct + '%)';
}
function sliderInput(el) {
  poetSeeking = true;
  paintSlider(el);
  document.getElementById('np-cur').textContent = fmt(el.value * 1000);
}
function sliderDone(el) {
  fetch('/api/player/seek', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'pos=' + (el.value * 1000) })
    .then(function () { setTimeout(function () { poetSeeking = false; }, 600); });
}

/* ---- theming ---- */
/* Status bar tracks the canvas: light mode paints it in the selected tint
   (dark icons), dark mode in the dark primary (light icons) — the native
   side derives icon contrast from the color's luminance. */
function poetSyncStatusBar() {
  if (window.PoetNative && PoetNative.setStatusBarColor)
    PoetNative.setStatusBarColor(window.POET.dark ? '#16151d' : (window.POET.themeBg || '#f2effa'));
}
function setAccent(c) {
  var r = document.documentElement.style;
  r.setProperty('--accent', c);
  r.setProperty('--accent-faint', c + '2e');
  r.setProperty('--accent-soft', c + '66');
  r.setProperty('--accent-shadow', c + '80');
  window.POET.accent = c;
  fetch('/api/settings/accent', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'c=' + encodeURIComponent(c) });
  document.querySelectorAll('.swatch').forEach(function (s) { s.classList.toggle('on', s.getAttribute('data-c') === c); });
}
function setTheme(name, bg) {
  /* The canvas tint is a light-mode choice: in dark mode the dark --bg from
     the stylesheet must win, so only pin an inline --bg when light. */
  if (window.POET.dark) document.documentElement.style.removeProperty('--bg');
  else document.documentElement.style.setProperty('--bg', bg);
  window.POET.theme = name;
  window.POET.themeBg = bg;
  poetSyncStatusBar();
  fetch('/api/settings/theme', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'name=' + encodeURIComponent(name) });
  document.querySelectorAll('.tint-pill').forEach(function (p) { p.classList.toggle('on', p.getAttribute('data-n') === name); });
}
function setDark(on) {
  window.POET.dark = on;
  var root = document.documentElement;
  if (on) {
    root.setAttribute('data-theme', 'dark');
    /* drop the inline light canvas tint so the dark --bg rule applies */
    root.style.removeProperty('--bg');
  } else {
    root.removeAttribute('data-theme');
    if (window.POET.themeBg) root.style.setProperty('--bg', window.POET.themeBg);
  }
  poetSyncStatusBar();
  fetch('/api/settings/dark', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'on=' + (on ? '1' : '0') });
  document.querySelectorAll('.theme-opt').forEach(function (b) { b.classList.toggle('on', (b.getAttribute('data-dark') === '1') === on); });
}

/* ---- onboarding ---- */
function dismissTip() {
  localStorage.setItem('poet-tip-dismissed', '1');
  var s = document.getElementById('tip-shield'); if (s) s.remove();
  var b = document.getElementById('tip-banner'); if (b) b.remove();
  var c = document.getElementById('folder-card'); if (c) c.style.zIndex = '1';
}
document.addEventListener('DOMContentLoaded', function () {
  /* Single deterministic first load. Previously the main container issued a
     load-triggered library fetch AND this handler issued a delayed settings
     redirect; whichever response landed last won, so the onboarding tip
     could appear and then be swapped away with no user interaction. */
  var firstRun = window.POET.folders === 0 && localStorage.getItem('poet-tip-dismissed') !== '1';
  poetGo(firstRun ? '/screens/settings?tip=1' : '/screens/library');
  poetPoll();
});

/* ---- tag editor ---- */
function poetInitEditor() {
  var f = document.getElementById('ed-form');
  if (!f) return;
  window.poetEd = {
    trackId: Number(f.getAttribute('data-track-id')),
    ext: f.getAttribute('data-ext') || '',
    hasArt: f.getAttribute('data-has-art') === '1',
    art: 'keep', lrc: [], lrcKey: null, tick: null
  };
  poetEdArtRender();
  poetEdRenamePreview();
  poetEd.tick = setInterval(poetLrcTick, 100);
}
function poetCloseEditor() {
  if (window.poetEd && poetEd.tick) clearInterval(poetEd.tick);
  window.poetEd = null;
  document.getElementById('modal-root').innerHTML = '';
}
function poetEdTab(name) {
  document.querySelectorAll('.ed-tab').forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-tab') === name); });
  document.querySelectorAll('.ed-pane').forEach(function (p) { p.hidden = p.getAttribute('data-pane') !== name; });
}
function poetEdToggleRename() {
  var sw = document.getElementById('ed-rename-switch');
  var on = !sw.classList.contains('on');
  sw.classList.toggle('on', on);
  document.getElementById('ed-rename-flag').value = on ? '1' : '0';
  document.getElementById('ed-rename-detail').hidden = !on;
  if (on) poetEdRenamePreview();
}
function poetEdRenamePreview() {
  var pat = (document.getElementById('ed-rename-pattern') || {}).value || '';
  var g = function (n) { var el = document.querySelector('#ed-form [name="' + n + '"]'); return el ? el.value.trim() : ''; };
  var track = g('trackNo') || '00';
  var out = pat.replace(/%track%/g, track).replace(/%title%/g, g('title') || 'Untitled')
    .replace(/%artist%/g, g('artist') || 'Unknown').replace(/%album%/g, g('album') || 'Album');
  out = out.replace(/[\\/:*?"<>|]/g, '_').replace(/\s+/g, ' ').trim();
  var prev = document.getElementById('ed-rename-preview');
  if (prev) prev.textContent = out;
}
function poetEdPickArt() {
  fetch('/api/tageditor/pick-art', { method: 'POST' });
}
/* called from native (MainActivity) once the gallery image is loaded */
function poetArtPicked() {
  if (!window.poetEd) return;
  poetEd.art = 'custom';
  document.getElementById('ed-art-action').value = 'custom';
  poetEdArtRender();
}
function poetEdArt(action) {
  if (!window.poetEd) return;
  poetEd.art = action;
  document.getElementById('ed-art-action').value = action;
  poetEdArtRender();
}
function poetEdArtRender() {
  var tile = document.getElementById('ed-art-tile');
  if (!tile) return;
  var showsArt = false, html;
  if (poetEd.art === 'custom') {
    html = '<img src="/api/tageditor/art-preview?t=' + Date.now() + '" alt="">'; showsArt = true;
  } else if (poetEd.art === 'keep' && poetEd.hasArt) {
    html = '<img src="/api/art/' + poetEd.trackId + '" alt="">'; showsArt = true;
  } else {
    html = '<div class="ed-art-empty"><div class="g">♪</div><div style="font-size:13px; font-weight:600; margin-top:4px;">No artwork</div></div>';
  }
  tile.innerHTML = html;
  /* header thumb tracks the choice; falls back to a note glyph when cleared */
  var head = document.getElementById('ed-head-art');
  if (head) head.innerHTML = showsArt ? html : '<span style="font-size:16px;">♪</span>';
  var remove = document.getElementById('ed-art-remove');
  var restore = document.getElementById('ed-art-restore');
  if (remove) remove.hidden = !showsArt;
  if (restore) restore.hidden = !(poetEd.art === 'remove' && poetEd.hasArt);
}
function poetEdLyricMode(mode) {
  document.querySelectorAll('.ed-seg-btn').forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-mode') === mode); });
  document.getElementById('ed-lyric-unsynced').hidden = mode !== 'unsynced';
  document.getElementById('ed-lyric-synced').hidden = mode !== 'synced';
  if (mode === 'synced') { poetLrcBuild(); poetLrcRender(); }
}
function poetLrcBuild() {
  var ta = document.getElementById('ed-lyrics');
  var lines = (ta ? ta.value : '').split('\n').map(function (x) { return x.trim(); }).filter(function (x) { return x.length; });
  var key = lines.join('');
  if (poetEd.lrcKey !== key) {
    poetEd.lrc = lines.map(function (t) { return { text: t, t: null }; });
    poetEd.lrcKey = key;
  }
}
function poetLrcPos() {
  var s = poetLastState;
  if (!s || s.trackId !== window.poetEd.trackId) return 0;
  var p = s.pos + (s.playing ? (Date.now() - s._at) : 0);
  if (s.dur > 0 && p > s.dur) p = s.dur;
  return p < 0 ? 0 : p;
}
function poetLrcPlay() {
  var s = poetLastState;
  if (s && s.trackId === window.poetEd.trackId) fetch('/api/player/toggle', { method: 'POST' });
  else fetch('/api/player/play/' + window.poetEd.trackId + '?ctx=songs', { method: 'POST' });
}
function poetLrcStamp() {
  if (!window.poetEd) return;
  var idx = poetEd.lrc.findIndex(function (l) { return l.t === null; });
  if (idx === -1) return;
  poetEd.lrc[idx].t = poetLrcPos();
  poetLrcRender();
}
function poetLrcReset() {
  if (!window.poetEd) return;
  poetEd.lrc.forEach(function (l) { l.t = null; });
  poetLrcRender();
}
function poetLrcSeek(i) {
  var l = window.poetEd && poetEd.lrc[i];
  if (l && l.t !== null && poetLastState && poetLastState.trackId === poetEd.trackId) {
    fetch('/api/player/seek', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'pos=' + Math.round(l.t) });
  }
}
function poetLrcPad(n) { return n < 10 ? '0' + n : '' + n; }
function poetLrcClock(ms) {
  var t = Math.max(0, Math.floor(ms)); var m = Math.floor(t / 60000); var s = Math.floor((t % 60000) / 1000); var cs = Math.floor((t % 1000) / 10);
  return poetLrcPad(m) + ':' + poetLrcPad(s) + '.' + poetLrcPad(cs);
}
function poetLrcRender() {
  var wrap = document.getElementById('ed-lrc-rows');
  if (!wrap || !window.poetEd) return;
  var lrc = poetEd.lrc;
  if (lrc.length === 0) {
    wrap.innerHTML = '<div class="ed-lrc-empty">Add lines in the Unsynced tab first, then stamp them here.</div>';
  } else {
    var nextIdx = lrc.findIndex(function (l) { return l.t === null; });
    wrap.innerHTML = lrc.map(function (l, i) {
      var cls = 'ed-lrc-row' + (l.t !== null ? ' stamped' : '') + (i === nextIdx ? ' next' : '');
      var stamp = l.t !== null ? '[' + poetLrcClock(l.t) + ']' : '[--:--.--]';
      var badge = i === nextIdx ? '<span class="ed-lrc-badge">NEXT</span>' : '';
      return '<div class="' + cls + '" data-i="' + i + '" onclick="poetLrcSeek(' + i + ')"><span class="stamp">' + stamp + '</span><span class="txt"></span>' + badge + '</div>';
    }).join('');
    /* set text via textContent to avoid injecting user lyric HTML */
    wrap.querySelectorAll('.ed-lrc-row').forEach(function (row) { row.querySelector('.txt').textContent = lrc[Number(row.getAttribute('data-i'))].text; });
  }
  var stamped = lrc.filter(function (l) { return l.t !== null; }).length;
  var count = document.getElementById('ed-lrc-count');
  if (count) count.textContent = stamped + ' of ' + lrc.length + ' lines stamped';
  var exp = document.getElementById('ed-lrc-export');
  if (exp) exp.textContent = (lrc.length > 0 && stamped === lrc.length) ? 'Export .lrc file ✓' : 'Export .lrc file (' + stamped + '/' + lrc.length + ')';
}
function poetLrcTick() {
  var clock = document.getElementById('ed-lrc-clock');
  if (!clock) { if (window.poetEd && poetEd.tick) { clearInterval(poetEd.tick); poetEd.tick = null; } return; }
  var synced = document.getElementById('ed-lyric-synced');
  if (!synced || synced.hidden) return;
  var pos = poetLrcPos();
  clock.textContent = poetLrcClock(pos);
  var play = document.getElementById('ed-lrc-play');
  if (play) { var pl = (poetLastState && poetLastState.trackId === poetEd.trackId && poetLastState.playing); if (play._pl !== pl) { play._pl = pl; play.innerHTML = pl ? ICON_PAUSE_SM : ICON_PLAY_SM; } }
  var active = -1;
  poetEd.lrc.forEach(function (l, i) { if (l.t !== null && l.t <= pos) active = i; });
  document.querySelectorAll('#ed-lrc-rows .ed-lrc-row').forEach(function (row) {
    row.classList.toggle('active', Number(row.getAttribute('data-i')) === active);
  });
}
function poetLrcExport() {
  if (!window.poetEd) return;
  var stamped = poetEd.lrc.filter(function (l) { return l.t !== null; }).sort(function (a, b) { return a.t - b.t; });
  if (stamped.length === 0) { poetToast('Stamp at least one line first'); return; }
  var text = stamped.map(function (l) { return '[' + poetLrcClock(l.t) + ']' + l.text; }).join('\n') + '\n';
  fetch('/api/tageditor/' + poetEd.trackId + '/save-lrc', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'lrc=' + encodeURIComponent(text)
  });
}

/* back-button support: Android calls poetBack() */
function poetBack() {
  if (document.getElementById('modal-root').innerHTML !== '') { poetCloseEditor(); document.getElementById('modal-root').innerHTML = ''; return 'handled'; }
  if (document.getElementById('sheet-root').innerHTML !== '') { closeSheet(); return 'handled'; }
  if (document.getElementById('queue-root').innerHTML !== '') { closeQueue(); return 'handled'; }
  if (document.querySelector('.menu')) { closeMenus(); return 'handled'; }
  if (poetInSelect()) { poetExitSelect(); return 'handled'; }
  if (poetScreenUrl !== '/screens/library') { poetGo('/screens/library'); return 'handled'; }
  return 'exit';
}
</script>
</body>
</html>"""
    }
}
