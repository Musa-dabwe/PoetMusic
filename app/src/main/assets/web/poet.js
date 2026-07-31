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
    /* the journal hangs off the brand mark, not off either nav pill */
    var onSettings = poetScreenUrl.indexOf('/screens/settings') === 0;
    var onJournal = poetScreenUrl.indexOf('/screens/journal') === 0;
    lib.classList.toggle('active', !onSettings && !onJournal);
    set.classList.toggle('active', onSettings);
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
  /* --accent-shadow is rethemed to a neutral in dark mode, so pinning the
     accent-tinted value inline here would outrank the stylesheet and bring
     the halo back (same hazard as --bg in setTheme). */
  if (window.POET.dark) r.removeProperty('--accent-shadow');
  else r.setProperty('--accent-shadow', c + '80');
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
    /* drop the inline light canvas tint so the dark --bg rule applies, and
       likewise the accent-tinted shadow so the dark neutral one applies */
    root.style.removeProperty('--bg');
    root.style.removeProperty('--accent-shadow');
  } else {
    root.removeAttribute('data-theme');
    if (window.POET.themeBg) root.style.setProperty('--bg', window.POET.themeBg);
    if (window.POET.accent) root.style.setProperty('--accent-shadow', window.POET.accent + '80');
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

/* ---- listening journal ---- */
function poetPickPortrait() {
  fetch('/api/journal/pick-portrait', { method: 'POST' });
}
/* called from native (MainActivity) once the gallery image is stored; the
   re-rendered screen carries a fresh ?v= stamp, so the new portrait shows
   instead of the cached one */
function poetPortraitPicked() {
  if (poetScreenUrl === '/screens/journal') poetGo('/screens/journal');
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
