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
  -webkit-user-select:none; user-select:none;
  /* blocks pinch and double-tap zoom while keeping scroll + tap responsive */
  touch-action: manipulation; }
input, textarea { -webkit-user-select:text; user-select:text; }
button { font-family: inherit; color: var(--ink); }
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
/* full-screen catcher behind an open menu: blocks click-through to the layout below */
.menu-shield { position:fixed; inset:0; z-index:29; background:transparent; }
/* lift the row that owns the open menu above sibling rows and the tray */
.row.menu-open { z-index:45; }
.row.menu-open:active { transform:none; }
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

/* bottom sheets (sort drawer, tag editor) */
.sheet-shield { position:fixed; inset:0; z-index:70; background:rgba(59,54,81,0.35); backdrop-filter:blur(2px); animation:poet-fade-in 0.2s ease; }
.sheet { position:fixed; bottom:0; left:50%; transform:translateX(-50%); width:100%; max-width:480px; z-index:71;
  background:#ffffff; border-radius:24px 24px 0 0; box-shadow:0 -12px 40px rgba(59,54,81,0.25); margin:0;
  padding:12px 20px calc(24px + env(safe-area-inset-bottom)) 20px; animation:poet-sheet-up 0.25s cubic-bezier(0.2,0.9,0.3,1); }
.sheet-tall { max-height:88vh; overflow-y:auto; }
.sheet-grab { width:40px; height:4px; border-radius:2px; background:rgba(59,54,81,0.15); margin:0 auto 14px auto; }
.sheet-title { font-size:16px; font-weight:700; }
.sheet-sub { font-size:12px; color:var(--muted); font-family:monospace; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.sheet-head { position:sticky; top:0; z-index:2; background:#ffffff; display:flex; align-items:center; justify-content:space-between; gap:12px; padding-bottom:14px; }

/* sort drawer */
.sortbtn { border:none; cursor:pointer; display:inline-flex; align-items:center; gap:7px; padding:10px 14px; border-radius:12px;
  background:rgba(59,54,81,0.06); color:var(--ink); font-size:13px; font-weight:700; flex-shrink:0; }
.sortbtn:active { transform: scale(0.95); }
.sortopt { display:flex; align-items:center; gap:14px; width:100%; border:none; cursor:pointer; text-align:left;
  padding:13px 14px; border-radius:14px; background:rgba(59,54,81,0.03); color:var(--ink); transition:background 0.15s; }
.sortopt:active { transform: scale(0.98); }
.sortopt.active { background: var(--accent-faint); }
.radio { width:20px; height:20px; border-radius:50%; border:2px solid var(--ink); box-sizing:border-box;
  display:flex; align-items:center; justify-content:center; flex-shrink:0; }
.radio-dot { width:10px; height:10px; border-radius:50%; background:var(--ink); animation:poet-pop 0.25s ease; }

/* confirmation card */
.confirm-card { width:100%; max-width:340px; background:#ffffff; border-radius:18px; padding:24px 22px 16px 22px;
  box-shadow:0 20px 60px rgba(59,54,81,0.35); animation:poet-fade 0.15s ease-out; }
.confirm-title { font-size:16px; font-weight:700; margin-bottom:8px; }
.confirm-msg { font-size:14px; line-height:1.5; color:var(--muted); }
.confirm-strong { color:var(--ink); font-weight:600; font-family:monospace; font-size:13px; word-break:break-all; }
.confirm-actions { display:flex; justify-content:flex-end; gap:8px; margin-top:20px; }
.btn-ghost { border:none; background:transparent; cursor:pointer; padding:11px 18px; border-radius:12px;
  font-size:13px; font-weight:700; letter-spacing:0.04em; color:var(--muted); }
.btn-ghost:active { transform: scale(0.95); background:rgba(59,54,81,0.05); }
.confirm-ok { letter-spacing:0.04em; font-size:13px; padding:11px 22px; }

/* tag editor fields */
.field { display:flex; flex-direction:column; gap:6px; }
.field span { font-size:12px; font-weight:700; color:var(--muted); letter-spacing:0.02em; }
.field input { font-family:inherit; font-size:14px; font-weight:600; color:var(--ink); padding:12px 14px; border-radius:12px;
  border:1.5px solid rgba(59,54,81,0.12); background:#ffffff; outline:none; transition:border-color 0.15s, box-shadow 0.15s; }
.field input:focus { border-color:var(--accent); box-shadow:0 0 0 3px var(--accent-faint); }

/* home screen widget preview block (Settings) */
.widget { background:var(--bg); border-radius:16px; padding:12px 14px; display:flex; align-items:center; gap:12px; }
.widget-art { width:40px; height:40px; border-radius:10px; background:var(--accent-faint); flex-shrink:0;
  display:flex; align-items:center; justify-content:center; font-size:12px; color:rgba(59,54,81,0.55); overflow:hidden; }
.widget-art img { width:100%; height:100%; object-fit:cover; }
.widget-meta { flex:1; min-width:0; }
.widget-title { font-size:14px; font-weight:700; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.widget-artist { font-size:12px; color:var(--muted); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.widget-time { font-size:12px; color:var(--muted); font-variant-numeric:tabular-nums; flex-shrink:0; }
.widget-controls { display:flex; align-items:center; gap:2px; flex-shrink:0; }
.widget-btn { border:none; background:transparent; cursor:pointer; width:32px; height:32px; display:flex; align-items:center; justify-content:center; border-radius:50%; }
.widget-btn:active { transform: scale(0.85); }
.widget-play { border:none; cursor:pointer; width:36px; height:36px; border-radius:50%; background:var(--accent); display:flex; align-items:center; justify-content:center; }
.widget-play:active { transform: scale(0.85); }

/* onboarding — no backdrop-filter here: blur on a full-screen fixed shield
   glitches Android WebView compositing (elements behind it vanish) */
#tip-shield { position:fixed; inset:0; z-index:40; background:rgba(59,54,81,0.35); }
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

/* queue panel */
@keyframes queue-up { from { transform:translate(-50%,100%); } to { transform:translate(-50%,0); } }
@keyframes queue-eq { 0%,100% { height:5px; } 50% { height:15px; } }
.queue-shield { position:fixed; inset:0; z-index:64; background:rgba(59,54,81,0.35); backdrop-filter:blur(2px); }
.queue-panel { position:fixed; bottom:0; left:50%; transform:translateX(-50%); width:100%; max-width:480px; max-height:86vh; z-index:65;
  background:var(--bg); border-radius:22px 22px 0 0; box-shadow:0 -8px 32px rgba(59,54,81,0.25);
  padding:16px 16px calc(14px + env(safe-area-inset-bottom)) 16px; display:flex; flex-direction:column; animation: queue-up 0.22s ease-out; }
#qp-body { overflow-y:auto; min-height:0; padding:2px 0 4px 0; }
.qp-hdr { display:flex; align-items:center; gap:12px; padding:0 2px 10px 2px; flex-shrink:0; }
.qp-back { border:none; cursor:pointer; width:38px; height:38px; border-radius:12px; background:rgba(59,54,81,0.06);
  display:flex; align-items:center; justify-content:center; flex-shrink:0; }
.qp-back:active { transform:scale(0.92); }
.qp-title { font-size:19px; font-weight:700; letter-spacing:-0.02em; }
.qp-src { font-size:12px; color:var(--muted); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.qp-clear { border:none; background:transparent; cursor:pointer; font-size:13px; font-weight:600; color:var(--muted); padding:8px 10px; border-radius:10px; flex-shrink:0; }
.qp-clear:active { transform:scale(0.95); background:rgba(59,54,81,0.06); }
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
.q-row.dragging { position:relative; z-index:5; background:#ffffff; box-shadow:0 8px 24px rgba(59,54,81,0.25); }
.q-num { width:20px; text-align:center; font-size:13px; font-weight:700; color:#b4aecb; font-variant-numeric:tabular-nums; flex-shrink:0; }
.q-x { border:none; background:transparent; cursor:pointer; width:32px; height:32px; border-radius:8px;
  display:flex; align-items:center; justify-content:center; color:#b4aecb; flex-shrink:0; }
.q-x:active { transform:scale(0.9); background:rgba(59,54,81,0.08); color:var(--muted); }
.q-grab { width:24px; padding:8px 0; display:flex; flex-direction:column; gap:3px; align-items:center; flex-shrink:0; cursor:grab; touch-action:none; }
.q-grab span { width:14px; height:2px; border-radius:2px; background:#cfc9de; }

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

  <div id="queue-root"></div>
  <div id="sheet-root"></div>
  <div id="modal-root"></div>
  <div id="toast"></div>
</div>

<script>
window.POET = { folders: $folderCount, accent: ${jsonStr(accent)}, theme: ${jsonStr(theme)} };

var ICON_PLAY_SM = '<svg width="14" height="16" viewBox="0 0 22 26" style="margin-left:3px;"><polygon points="0,0 22,13 0,26" fill="#3b3651"></polygon></svg>';
var ICON_PAUSE_SM = '<div style="display:flex; gap:4px;"><div style="width:4px; height:15px; background:#3b3651; border-radius:2px;"></div><div style="width:4px; height:15px; background:#3b3651; border-radius:2px;"></div></div>';
var ICON_PLAY_LG = '<svg width="22" height="26" viewBox="0 0 22 26" style="margin-left:4px;"><polygon points="0,0 22,13 0,26" fill="#3b3651"></polygon></svg>';
var ICON_PAUSE_LG = '<div style="display:flex; gap:6px;"><div style="width:6px; height:24px; background:#3b3651; border-radius:2px;"></div><div style="width:6px; height:24px; background:#3b3651; border-radius:2px;"></div></div>';

/* home screen widget preview block */
var ICON_PLAY_W = '<svg width="11" height="13" viewBox="0 0 22 26" style="margin-left:2px;"><polygon points="0,0 22,13 0,26" fill="#3b3651"></polygon></svg>';
var ICON_PAUSE_W = '<div style="display:flex; gap:3px;"><div style="width:3px; height:12px; background:#3b3651; border-radius:2px;"></div><div style="width:3px; height:12px; background:#3b3651; border-radius:2px;"></div></div>';
var ICON_HEART_ON = '<svg width="17" height="16" viewBox="0 0 24 22" style="animation:poet-pop 0.3s ease;"><path d="M12 21 C-6 10 3 -3 12 5 C21 -3 30 10 12 21 Z" fill="#e79ab0"></path></svg>';
var ICON_HEART_OFF = '<svg width="17" height="16" viewBox="0 0 24 22"><path d="M12 20 C-4.5 9.5 3.5 -1.5 12 5.5 C20.5 -1.5 28.5 9.5 12 20 Z" fill="none" stroke="#8a84a3" stroke-width="2"></path></svg>';

/* shuffle button: crossed arrows = shuffle all, ordered list = play in order */
var ICON_SHUFFLE_ON = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 3 21 3 21 8"></polyline><line x1="4" y1="20" x2="21" y2="3"></line><polyline points="21 16 21 21 16 21"></polyline><line x1="15" y1="15" x2="21" y2="21"></line><line x1="4" y1="4" x2="9" y2="9"></line></svg>';
var ICON_SHUFFLE_OFF = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="6" x2="20" y2="6"></line><line x1="4" y1="12" x2="20" y2="12"></line><line x1="4" y1="18" x2="12" y2="18"></line><polyline points="16 15 19 18 16 21"></polyline></svg>';
/* repeat button, indexed by mode: 0 off, 1 repeat one, 2 repeat playlist, 3 play single & stop */
var ICON_REPEAT = [
  '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="12" x2="19" y2="12"></line><polyline points="13 6 19 12 13 18"></polyline></svg>',
  '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="17 1 21 5 17 9"></polyline><path d="M3 11V9a4 4 0 0 1 4-4h14"></path><polyline points="7 23 3 19 7 15"></polyline><path d="M21 13v2a4 4 0 0 1-4 4H3"></path><text x="12" y="15" font-size="9" stroke-width="1" fill="currentColor" text-anchor="middle">1</text></svg>',
  '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="17 1 21 5 17 9"></polyline><path d="M3 11V9a4 4 0 0 1 4-4h14"></path><polyline points="7 23 3 19 7 15"></polyline><path d="M21 13v2a4 4 0 0 1-4 4H3"></path></svg>',
  '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><text x="8" y="17" font-size="13" stroke-width="1" fill="currentColor" text-anchor="middle">1</text><rect x="14" y="9" width="6" height="6" fill="currentColor" stroke="none"></rect></svg>'
];
var REPEAT_TITLES = ['Play queue through', 'Repeat one song', 'Repeat playlist', 'Play single song and stop'];

var poetScreenUrl = '/screens/library';
var poetSeeking = false;
var poetShownTrack = -1;
var poetTrayTrack = -2;
var poetLastState = null;
var poetQueueDrag = false;

function fmt(ms) {
  var s = Math.floor(ms / 1000), m = Math.floor(s / 60); s = s % 60;
  return m + ':' + (s < 10 ? '0' + s : s);
}

/* mm:ss with zero-padded minutes, for the widget preview */
function fmtClock(ms) {
  var s = Math.floor(ms / 1000), m = Math.floor(s / 60); s = s % 60;
  return (m < 10 ? '0' + m : m) + ':' + (s < 10 ? '0' + s : s);
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
document.body.addEventListener('poet-refresh', function () { closeMenus(); poetGo(poetScreenUrl); });
document.body.addEventListener('poet-close-modal', function () { document.getElementById('modal-root').innerHTML = ''; });
document.body.addEventListener('poet-goto', function (e) { closeMenus(); poetGo(e.detail.value || e.detail); });

document.body.addEventListener('htmx:afterSwap', function (e) {
  var t = e.detail.target;
  if (t && t.classList && t.classList.contains('menu-slot')) {
    var row = t.closest('.row');
    if (row) row.classList.add('menu-open');
    var menu = t.querySelector('.menu');
    if (menu) positionMenu(menu);
  }
  if (e.detail.target && e.detail.target.id === 'main-container') {
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

  /* home screen widget preview (Settings): live position + controls */
  var wTime = document.getElementById('w-time');
  if (wTime) {
    wTime.textContent = fmtClock(s.pos);
    var wPlay = document.getElementById('w-play');
    if (wPlay) wPlay.innerHTML = s.playing ? ICON_PAUSE_W : ICON_PLAY_W;
    var wFav = document.getElementById('w-fav');
    if (wFav && wFav._fav !== !!s.fav) { wFav._fav = !!s.fav; wFav.innerHTML = s.fav ? ICON_HEART_ON : ICON_HEART_OFF; }
    var wArt = document.getElementById('w-art');
    if (wArt && Number(wArt.getAttribute('data-track-id')) !== s.trackId) {
      wArt.setAttribute('data-track-id', s.trackId);
      wArt.innerHTML = s.trackId >= 0 ? '<img src="/api/art/' + s.trackId + '" alt="">' : '♪';
      document.getElementById('w-title').textContent = s.title;
      document.getElementById('w-artist').textContent = s.artist;
    }
  }

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
      sh.classList.toggle('on', s.shuffle);
      var shIcon = s.shuffle ? ICON_SHUFFLE_ON : ICON_SHUFFLE_OFF;
      if (sh._icon !== shIcon) { sh._icon = shIcon; sh.innerHTML = shIcon; sh.title = s.shuffle ? 'Shuffle all' : 'Play in order'; }
    }
    var rp = document.getElementById('np-repeat');
    if (rp) {
      rp.classList.toggle('on', s.repeat !== 0);
      var rpIcon = ICON_REPEAT[s.repeat] || ICON_REPEAT[0];
      if (rp._icon !== rpIcon) { rp._icon = rpIcon; rp.innerHTML = rpIcon; rp.title = REPEAT_TITLES[s.repeat] || REPEAT_TITLES[0]; }
    }
    var sp = document.getElementById('np-speed');
    if (sp) sp.textContent = s.speed.toFixed(2).replace(/0$/, '').replace(/\.0$/, '.0') + 'x';
    var sl = document.getElementById('np-sleep');
    if (sl) sl.textContent = s.sleep >= 0 ? 'sleep ' + Math.ceil(s.sleep / 60000) + 'm' : 'sleep';
    var fv = document.getElementById('np-fav');
    if (fv) fv.classList.toggle('on', !!s.fav);
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
  if (window.PoetNative && PoetNative.setStatusBarColor) PoetNative.setStatusBarColor(c);
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
  /* Single deterministic first load. Previously the main container issued a
     load-triggered library fetch AND this handler issued a delayed settings
     redirect; whichever response landed last won, so the onboarding tip
     could appear and then be swapped away with no user interaction. */
  var firstRun = window.POET.folders === 0 && localStorage.getItem('poet-tip-dismissed') !== '1';
  poetGo(firstRun ? '/screens/settings?tip=1' : '/screens/library');
  poetPoll();
});

/* back-button support: Android calls poetBack() */
function poetBack() {
  if (document.getElementById('modal-root').innerHTML !== '') { document.getElementById('modal-root').innerHTML = ''; return 'handled'; }
  if (document.getElementById('sheet-root').innerHTML !== '') { closeSheet(); return 'handled'; }
  if (document.getElementById('queue-root').innerHTML !== '') { closeQueue(); return 'handled'; }
  if (document.querySelector('.menu')) { closeMenus(); return 'handled'; }
  if (poetScreenUrl !== '/screens/library') { poetGo('/screens/library'); return 'handled'; }
  return 'exit';
}
</script>
</body>
</html>"""
    }
}
