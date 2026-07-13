package com.musa.poetmusic.server

/**
 * The single-page shell: Poet design-system CSS, the htmx runtime, the
 * persistent header + bottom media tray, and the client-side glue JS
 * (state poller, long-press menus, onboarding, theming, toasts).
 */
object Shell {

    val CANVAS_TINTS = mapOf("Lavender" to "#f2effa", "Cream" to "#faf5ec", "Sage" to "#eff6f0")
    val ACCENTS = listOf("#b9a5ec", "#9fd8c0", "#f4b89a", "#a5c9ec")

    fun page(accent: String, theme: String, folderCount: Int): String {
        val bg = CANVAS_TINTS[theme] ?: CANVAS_TINTS.getValue("Lavender")
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Poet Music</title>
<script src="/assets/htmx.min.js"></script>
<style>
@font-face { font-family:'Outfit'; font-style:normal; font-weight:400 700; font-display:swap;
  src:url('/assets/outfit-latin.woff2') format('woff2');
  unicode-range:U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+2000-206F,U+2074,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD; }
@font-face { font-family:'Outfit'; font-style:normal; font-weight:400 700; font-display:swap;
  src:url('/assets/outfit-latin-ext.woff2') format('woff2');
  unicode-range:U+0100-024F,U+0259,U+1E00-1EFF,U+2020,U+20A0-20AB,U+20AD-20CF,U+2113,U+2C60-2C7F,U+A720-A7FF; }

:root {
  --accent: $accent;
  --accent-faint: ${accent}2e;
  --accent-soft: ${accent}66;
  --accent-shadow: ${accent}80;
  --bg: $bg;
  --ink: #3b3651;
  --muted: #8a84a3;
  --lyric-dim: #a49ec0;
}
* { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
html, body { margin:0; padding:0; }
body { background: var(--bg); font-family:'Outfit', sans-serif; color: var(--ink); transition: background 0.25s;
  -webkit-user-select:none; user-select:none; }
input, textarea { -webkit-user-select:text; user-select:text; }
button { font-family: inherit; color: var(--ink); }
@keyframes poet-spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
@keyframes poet-bob { 0%,100% { transform: translateY(0); } 50% { transform: translateY(6px); } }
@keyframes poet-fade { from { opacity:0; transform: translateY(6px);} to { opacity:1; transform: translateY(0);} }

#app { width:100%; max-width:480px; min-height:100vh; margin:0 auto; position:relative; display:flex; flex-direction:column; }
#main-container { flex:1; padding:6px 20px 130px 20px; }
.screen { animation: poet-fade 0.18s ease-out; }

/* header */
.hdr { display:flex; align-items:center; justify-content:space-between; padding:18px 20px 10px 20px; }
.hdr-brand { display:flex; align-items:center; gap:10px; cursor:pointer; }
.hdr-logo { width:30px; height:30px; border-radius:9px; background:var(--accent); display:flex; align-items:center; justify-content:center; font-weight:700; font-size:15px; }
.hdr-name { font-size:20px; font-weight:700; letter-spacing:-0.02em; }
.navpill { border:none; cursor:pointer; padding:8px 14px; border-radius:99px; font-size:13px; font-weight:600; background:transparent; transition: background 0.15s, transform 0.12s; }
.navpill:active { transform: scale(0.95); }
.navpill.active { background: var(--accent-faint); }

/* generic */
.pill { border:none; cursor:pointer; font-size:13px; font-weight:600; padding:8px 14px; border-radius:99px; background:rgba(59,54,81,0.06); transition: background 0.15s, transform 0.12s; }
.pill:active { transform: scale(0.95); }
.pill.active { background: var(--accent); }
.btn-primary { border:none; cursor:pointer; display:inline-flex; align-items:center; gap:8px; padding:10px 18px; border-radius:12px; background:var(--accent); font-size:14px; font-weight:700; box-shadow:0 2px 8px var(--accent-shadow); transition: transform 0.12s; }
.btn-primary:active { transform: scale(0.95); }
.btn-outline { border:1.5px solid var(--accent); cursor:pointer; display:inline-flex; align-items:center; gap:8px; padding:10px 18px; border-radius:12px; background:#ffffff; font-size:14px; font-weight:700; transition: transform 0.12s; }
.btn-outline:active { transform: scale(0.95); }
.card { background:#ffffff; border-radius:18px; padding:18px; box-shadow:0 2px 10px rgba(59,54,81,0.06); }
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

/* context menu */
.menu { position:absolute; right:8px; top:52px; z-index:30; background:rgba(255,255,255,0.92); backdrop-filter:blur(12px); border:1px solid rgba(59,54,81,0.1); border-radius:12px; box-shadow:0 8px 24px rgba(59,54,81,0.18); overflow:hidden; min-width:180px; animation: poet-fade 0.14s ease-out; }
.menu button { display:block; width:100%; text-align:left; border:none; background:transparent; cursor:pointer; font-size:13px; font-weight:500; padding:11px 16px; color:var(--ink); }
.menu button:active { background: var(--accent-faint); }
.menu .menu-form { display:flex; gap:6px; padding:8px 10px; }
.menu .menu-form input { flex:1; min-width:0; border:1.5px solid rgba(59,54,81,0.15); border-radius:8px; padding:6px 8px; font-family:inherit; font-size:13px; }
.menu .menu-form button { width:auto; padding:6px 10px; border-radius:8px; background:var(--accent); font-weight:700; }

/* album grid */
.grid { display:grid; grid-template-columns:1fr 1fr; gap:14px; }
.album-card { cursor:pointer; transition: transform 0.1s; }
.album-card:active { transform: scale(0.97); }
.album-art { aspect-ratio:1; border-radius:16px; display:flex; align-items:center; justify-content:center; font-size:12px; font-weight:600; color:rgba(59,54,81,0.55); overflow:hidden; }
.album-art img { width:100%; height:100%; object-fit:cover; }

/* now playing */
.np-art { width:min(78vw,320px); aspect-ratio:1; border-radius:24px; box-shadow:0 16px 40px var(--accent-shadow); display:flex; align-items:center; justify-content:center; margin:0 auto 24px auto; overflow:hidden; }
.np-art img { width:100%; height:100%; object-fit:cover; }
.np-dot { border:none; cursor:pointer; width:44px; height:44px; border-radius:50%; background:rgba(59,54,81,0.06); font-size:18px; display:flex; align-items:center; justify-content:center; color:var(--ink); }
.np-dot:active { transform: scale(0.9); }
.np-dot.on { background: var(--accent-soft); }
.np-side { border:none; cursor:pointer; width:52px; height:52px; border-radius:50%; background:#ffffff; box-shadow:0 2px 8px rgba(59,54,81,0.12); display:flex; align-items:center; justify-content:center; }
.np-side:active { transform: scale(0.9); }
.np-main { border:none; cursor:pointer; width:76px; height:76px; border-radius:50%; background:var(--accent); box-shadow:0 8px 20px var(--accent-shadow); display:flex; align-items:center; justify-content:center; transition: transform 0.12s; }
.np-main:active { transform: scale(0.92); }
.chip { border:none; cursor:pointer; padding:9px 16px; border-radius:99px; background:rgba(59,54,81,0.06); font-size:13px; font-weight:600; color:var(--ink); }
.chip:active { transform: scale(0.95); }
.chip.on { background: var(--accent-soft); }
input.seek { -webkit-appearance:none; appearance:none; width:100%; height:5px; border-radius:3px; outline:none; border:none; cursor:pointer; }
input.seek::-webkit-slider-thumb { -webkit-appearance:none; appearance:none; width:18px; height:18px; border-radius:50%; background:var(--ink); border:3px solid #ffffff; box-shadow:0 1px 4px rgba(59,54,81,0.35); }
.lyrics-deck { width:100%; max-width:340px; margin:16px auto 0 auto; background:rgba(255,255,255,0.7); backdrop-filter:blur(8px); border-radius:16px; padding:18px 20px; display:flex; flex-direction:column; gap:10px; max-height:38vh; overflow-y:auto; }
.lyric { font-size:13px; font-weight:500; color:var(--lyric-dim); transition: all 0.3s; }
.lyric.active { font-size:16px; font-weight:700; color:var(--ink); }

/* settings */
.folder-row { display:flex; align-items:center; gap:10px; padding:10px 12px; border-radius:12px; background:var(--accent-faint); }
.folder-path { flex:1; font-size:13px; font-family:monospace; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.swatch { width:40px; height:40px; border-radius:50%; cursor:pointer; border:3px solid #ffffff; transition: transform 0.12s; }
.swatch:active { transform: scale(0.9); }
.swatch.on { border-color: var(--ink); }
.tint-pill { border:1.5px solid rgba(59,54,81,0.15); cursor:pointer; padding:9px 16px; border-radius:99px; font-size:13px; font-weight:600; color:var(--ink); }
.tint-pill.on { border-color: var(--ink); }
.spinner { display:inline-block; width:15px; height:15px; border:2.5px solid var(--accent); border-top-color:transparent; border-radius:50%; animation:poet-spin 0.8s linear infinite; }

/* onboarding */
#tip-shield { position:fixed; inset:0; z-index:40; background:rgba(59,54,81,0.35); backdrop-filter:blur(1px); }
.tip-banner { position:absolute; left:24px; right:24px; top:100%; margin-top:14px; z-index:60; animation:poet-bob 2.2s ease-in-out infinite; }
.tip-arrow { position:absolute; top:-7px; left:50%; width:14px; height:14px; background:var(--ink); transform:translateX(-50%) rotate(45deg); }
.tip-body { background:var(--ink); color:#f5f3fa; border-radius:14px; padding:16px 18px; box-shadow:0 12px 32px rgba(59,54,81,0.35); }

/* tray */
#tray { position:fixed; bottom:0; left:50%; transform:translateX(-50%); width:100%; max-width:480px; z-index:35; background:rgba(255,255,255,0.85); backdrop-filter:blur(16px); border-top:1px solid rgba(59,54,81,0.08); padding:10px 16px calc(10px + env(safe-area-inset-bottom)) 16px; }
#tray-progress { position:absolute; top:0; left:0; height:3px; background:var(--accent); width:0%; transition:width 0.4s linear; }
.tray-inner { display:flex; align-items:center; gap:12px; }
.tray-info { display:flex; align-items:center; gap:12px; flex:1; min-width:0; cursor:pointer; }
.tray-art { width:42px; height:42px; border-radius:10px; flex-shrink:0; display:flex; align-items:center; justify-content:center; font-size:11px; font-weight:600; color:rgba(59,54,81,0.55); overflow:hidden; background:var(--accent-faint); }
.tray-art img { width:100%; height:100%; object-fit:cover; }
.tray-btn { border:none; background:transparent; cursor:pointer; width:40px; height:40px; display:flex; align-items:center; justify-content:center; border-radius:50%; }
.tray-btn:active { transform: scale(0.9); }
.tray-play { border:none; cursor:pointer; width:48px; height:48px; border-radius:50%; background:var(--accent); display:flex; align-items:center; justify-content:center; box-shadow:0 3px 10px var(--accent-shadow); }
.tray-play:active { transform: scale(0.9); }

/* modal + toast */
#modal-root .modal-shield { position:fixed; inset:0; z-index:70; background:rgba(59,54,81,0.35); backdrop-filter:blur(2px); display:flex; align-items:center; justify-content:center; padding:24px; }
.modal { background:#ffffff; border-radius:18px; padding:20px; width:100%; max-width:400px; box-shadow:0 16px 48px rgba(59,54,81,0.3); animation:poet-fade 0.15s ease-out; }
.modal label { display:block; font-size:12px; font-weight:600; color:var(--muted); margin:12px 0 4px 0; }
.modal input { width:100%; border:1.5px solid rgba(59,54,81,0.15); border-radius:10px; padding:10px 12px; font-family:inherit; font-size:14px; color:var(--ink); background:#fff; }
.modal input:focus { outline:none; border-color: var(--accent); }
#toast { position:fixed; bottom:96px; left:50%; transform:translateX(-50%); z-index:90; background:var(--ink); color:#f5f3fa; font-size:13px; font-weight:600; padding:10px 18px; border-radius:99px; box-shadow:0 8px 24px rgba(59,54,81,0.35); opacity:0; pointer-events:none; transition:opacity 0.25s; max-width:85vw; text-align:center; }
#toast.show { opacity:1; }

.empty { text-align:center; color:var(--muted); padding:48px 20px; font-size:14px; }
.searchrow { display:flex; gap:8px; margin-bottom:14px; }
.searchrow input { flex:1; min-width:0; border:none; border-radius:12px; padding:10px 14px; font-family:inherit; font-size:14px; color:var(--ink); background:rgba(59,54,81,0.06); }
.searchrow input:focus { outline:2px solid var(--accent); }
.searchrow select { border:none; border-radius:12px; padding:10px; font-family:inherit; font-size:13px; font-weight:600; color:var(--ink); background:rgba(59,54,81,0.06); }
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

  <div id="main-container" hx-get="/screens/library" hx-trigger="load" hx-target="this"></div>

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

  <div id="modal-root"></div>
  <div id="toast"></div>
</div>

<script>
window.POET = { folders: $folderCount, accent: ${jsonStr(accent)}, theme: ${jsonStr(theme)} };

var ICON_PLAY_SM = '<svg width="14" height="16" viewBox="0 0 22 26" style="margin-left:3px;"><polygon points="0,0 22,13 0,26" fill="#3b3651"></polygon></svg>';
var ICON_PAUSE_SM = '<div style="display:flex; gap:4px;"><div style="width:4px; height:15px; background:#3b3651; border-radius:2px;"></div><div style="width:4px; height:15px; background:#3b3651; border-radius:2px;"></div></div>';
var ICON_PLAY_LG = '<svg width="22" height="26" viewBox="0 0 22 26" style="margin-left:4px;"><polygon points="0,0 22,13 0,26" fill="#3b3651"></polygon></svg>';
var ICON_PAUSE_LG = '<div style="display:flex; gap:6px;"><div style="width:6px; height:24px; background:#3b3651; border-radius:2px;"></div><div style="width:6px; height:24px; background:#3b3651; border-radius:2px;"></div></div>';

var poetScreenUrl = '/screens/library';
var poetSeeking = false;
var poetShownTrack = -1;
var poetTrayTrack = -2;
var poetLastState = null;

function fmt(ms) {
  var s = Math.floor(ms / 1000), m = Math.floor(s / 60); s = s % 60;
  return m + ':' + (s < 10 ? '0' + s : s);
}

function poetGo(url) { htmx.ajax('GET', url, { target: '#main-container', swap: 'innerHTML' }); }

function poetToast(msg) {
  var t = document.getElementById('toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(t._h);
  t._h = setTimeout(function () { t.classList.remove('show'); }, 2200);
}

function closeMenus() {
  document.querySelectorAll('.menu-slot').forEach(function (m) { m.innerHTML = ''; });
}

document.addEventListener('click', function (e) {
  if (!e.target.closest('.menu') && !e.target.closest('.row-menu-btn')) closeMenus();
});

document.body.addEventListener('poet-toast', function (e) { poetToast(e.detail.value || e.detail); });
document.body.addEventListener('poet-refresh', function () { closeMenus(); poetGo(poetScreenUrl); });
document.body.addEventListener('poet-close-modal', function () { document.getElementById('modal-root').innerHTML = ''; });
document.body.addEventListener('poet-goto', function (e) { closeMenus(); poetGo(e.detail.value || e.detail); });

document.body.addEventListener('htmx:afterSwap', function (e) {
  if (e.detail.target && e.detail.target.id === 'main-container') {
    var path = e.detail.pathInfo && (e.detail.pathInfo.finalRequestPath || e.detail.pathInfo.requestPath);
    if (path && path.indexOf('/screens/') === 0) poetScreenUrl = path;
    var lib = document.getElementById('nav-library'), set = document.getElementById('nav-settings');
    lib.classList.toggle('active', poetScreenUrl.indexOf('/screens/settings') !== 0);
    set.classList.toggle('active', poetScreenUrl.indexOf('/screens/settings') === 0);
    poetShownTrack = -1; // force refresh of now-playing fields
    window.scrollTo(0, 0);
  }
});

/* ---- long-press on rows: open the hidden context menu ---- */
(function () {
  var timer = null, fired = false, startX = 0, startY = 0;
  document.addEventListener('touchstart', function (e) {
    var row = e.target.closest('.row[data-menu-url]');
    if (!row) return;
    fired = false;
    startX = e.touches[0].clientX; startY = e.touches[0].clientY;
    timer = setTimeout(function () {
      fired = true;
      if (navigator.vibrate) navigator.vibrate(15);
      closeMenus();
      var slot = row.querySelector('.menu-slot');
      if (slot) htmx.ajax('GET', row.getAttribute('data-menu-url'), { target: slot, swap: 'innerHTML' });
    }, 480);
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

/* ---- live playback state poller ---- */
function applyState(s) {
  poetLastState = s;
  var pct = s.dur > 0 ? (s.pos / s.dur * 100) : 0;
  document.getElementById('tray-progress').style.width = pct + '%';
  document.getElementById('tray-play').innerHTML = s.playing ? ICON_PAUSE_SM : ICON_PLAY_SM;
  if (s.trackId !== poetTrayTrack) {
    poetTrayTrack = s.trackId;
    document.getElementById('tray-title').textContent = s.title;
    document.getElementById('tray-artist').textContent = s.artist;
    var art = document.getElementById('tray-art');
    art.innerHTML = s.trackId >= 0 ? '<img src="/api/art/' + s.trackId + '" alt="">' : '♪';
  }
  document.querySelectorAll('.row[data-track-id]').forEach(function (r) {
    r.classList.toggle('playing', Number(r.getAttribute('data-track-id')) === s.trackId);
  });

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
    if (sh) sh.classList.toggle('on', s.shuffle);
    var rp = document.getElementById('np-repeat');
    if (rp) { rp.classList.toggle('on', s.repeat !== 0); rp.innerHTML = s.repeat === 1 ? '↻&sup1;' : '↻'; }
    var sp = document.getElementById('np-speed');
    if (sp) sp.textContent = s.speed.toFixed(2).replace(/0$/, '').replace(/\.0$/, '.0') + '×';
    var sl = document.getElementById('np-sleep');
    if (sl) sl.textContent = s.sleep >= 0 ? '☾ ' + Math.ceil(s.sleep / 60000) + 'm' : '☾ Sleep';
    var deck = document.getElementById('lyrics-deck');
    if (deck) {
      var lines = deck.querySelectorAll('.lyric'), active = null;
      lines.forEach(function (l) {
        if (Number(l.getAttribute('data-at')) <= s.pos) active = l;
      });
      lines.forEach(function (l) { l.classList.toggle('active', l === active); });
      if (active && !poetSeeking) active.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
  }
}

function poetPoll() {
  fetch('/api/player/state').then(function (r) { return r.json(); }).then(applyState).catch(function () {});
}
setInterval(poetPoll, 1000);

function paintSlider(el) {
  var pct = el.max > 0 ? (el.value / el.max * 100) : 0;
  el.style.background = 'linear-gradient(to right, var(--accent) ' + pct + '%, rgba(59,54,81,0.12) ' + pct + '%)';
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
  document.documentElement.style.setProperty('--bg', bg);
  window.POET.theme = name;
  fetch('/api/settings/theme', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'name=' + encodeURIComponent(name) });
  document.querySelectorAll('.tint-pill').forEach(function (p) { p.classList.toggle('on', p.getAttribute('data-n') === name); });
}

/* ---- onboarding ---- */
function dismissTip() {
  localStorage.setItem('poet-tip-dismissed', '1');
  var s = document.getElementById('tip-shield'); if (s) s.remove();
  var b = document.getElementById('tip-banner'); if (b) b.remove();
  var c = document.getElementById('folder-card'); if (c) c.style.zIndex = '1';
}
document.addEventListener('DOMContentLoaded', function () {
  if (window.POET.folders === 0 && localStorage.getItem('poet-tip-dismissed') !== '1') {
    setTimeout(function () { poetGo('/screens/settings?tip=1'); }, 250);
  }
  poetPoll();
});

/* back-button support: Android calls poetBack() */
function poetBack() {
  if (document.getElementById('modal-root').innerHTML !== '') { document.getElementById('modal-root').innerHTML = ''; return 'handled'; }
  if (document.querySelector('.menu')) { closeMenus(); return 'handled'; }
  if (poetScreenUrl !== '/screens/library') { poetGo('/screens/library'); return 'handled'; }
  return 'exit';
}
</script>
</body>
</html>"""
    }
}
