package com.musa.poetmusic.server

import com.musa.poetmusic.data.LibraryStore
import com.musa.poetmusic.playback.QueueItem

/** Slide-up queue panel (Musicolet-style). */
object QueueViews {

    private val ICON_QP_PLAY = """<svg width="12" height="14" viewBox="0 0 22 26" style="margin-left:2px;"><polygon points="0,0 22,13 0,26" fill="currentColor"></polygon></svg>"""
    private val ICON_QP_PAUSE = """<div style="display:flex; gap:4px;"><div style="width:4px; height:14px; background:currentColor; border-radius:2px;"></div><div style="width:4px; height:14px; background:currentColor; border-radius:2px;"></div></div>"""

    /**
     * Slide-up queue panel (Musicolet-style): pinned Now Playing card with EQ
     * bars + play/pause, then the static "Next up" sequence with per-row
     * remove and drag-to-reorder handles. The header is rendered once on
     * open; #qp-body is re-rendered by every queue mutation and by the state
     * poller when the current track changes.
     */
    fun queuePanel(db: LibraryStore, items: List<QueueItem>, playing: Boolean, sourceName: String): String = """
        <div class="queue-shield" onclick="closeQueue()"></div>
        <div class="queue-panel">
          <div class="sheet-grab"></div>
          <div class="qp-hdr">
            <button class="qp-back" onclick="closeQueue()" aria-label="Close queue">
              <svg width="14" height="8" viewBox="0 0 14 8"><path d="M1 1 L7 7 L13 1" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"></path></svg>
            </button>
            <div style="flex:1; min-width:0;">
              <div class="qp-title">Queue</div>
              <div class="qp-src">Playing from ${esc(sourceName)}</div>
            </div>
            <button class="qp-clear" hx-post="/api/queue/clear" hx-target="#qp-body" hx-swap="innerHTML">Clear</button>
          </div>
          <div id="qp-body">${queuePanelBody(db, items, playing)}</div>
        </div>"""

    /** Inner content of the queue panel; swapped on every queue change. */
    fun queuePanelBody(db: LibraryStore, items: List<QueueItem>, playing: Boolean): String {
        val curIdx = items.indexOfFirst { it.current }
        if (items.isEmpty() || curIdx < 0) {
            return """
            <div id="qp-state" data-track-id="-1" hidden></div>
            <div class="empty">
              <div style="font-size:30px; margin-bottom:8px; opacity:0.5;">♪</div>
              Nothing playing yet.<br>Pick a song from your library.
            </div>"""
        }
        val cur = items[curIdx]
        val upcoming = items.drop(curIdx + 1)
        val byId = db.tracksByIds(items.map { it.trackId }).associateBy { it.id }
        val curTrack = byId[cur.trackId]

        val nowCard = """
        <div class="qp-label">Now playing</div>
        <div class="qp-now">
          <div class="row-art" style="width:52px; height:52px; border-radius:12px;"><img src="/api/art/${cur.trackId}?v=${curTrack?.lastModified ?: 0}" alt=""></div>
          <div class="row-main">
            <div class="row-title" style="font-size:15px; font-weight:700;">${esc(cur.title)}</div>
            <div class="row-sub">${esc(cur.artist)}</div>
          </div>
          <div id="qp-eq" class="qp-eq${if (playing) "" else " off"}"><span></span><span></span><span></span></div>
          <button id="qp-play" class="qp-playbtn" hx-post="/api/player/toggle" hx-swap="none" aria-label="Play or pause">${if (playing) ICON_QP_PAUSE else ICON_QP_PLAY}</button>
        </div>"""

        val totalMin = Math.round(upcoming.sumOf { byId[it.trackId]?.durationMs ?: 0L } / 60000.0)
        val meta = "${upcoming.size} ${if (upcoming.size == 1) "song" else "songs"} · $totalMin min"

        val nextUp = if (upcoming.isEmpty()) """
        <div class="qp-label" style="margin-top:26px;"><span>Next up</span></div>
        <div class="empty" style="padding:32px 20px;">
          <div style="font-size:30px; margin-bottom:8px; opacity:0.5;">♪</div>
          <div style="font-size:14px; font-weight:600; color:var(--ink);">Nothing in the queue</div>
          <div style="font-size:12px; margin-top:2px;">Add songs to keep the music going.</div>
        </div>"""
        else {
            val rows = upcoming.mapIndexed { pos, it ->
                val dur = byId[it.trackId]?.durationMs ?: 0L
                """
                <div class="q-row" data-qi="${it.index}">
                  <div class="q-num">${pos + 1}</div>
                  <div class="row-art" hx-post="/api/queue/jump/${it.index}" hx-target="#qp-body" hx-swap="innerHTML"><img loading="lazy" src="/api/art/${it.trackId}?v=${byId[it.trackId]?.lastModified ?: 0}" alt=""></div>
                  <div class="row-main" hx-post="/api/queue/jump/${it.index}" hx-target="#qp-body" hx-swap="innerHTML">
                    <div class="row-title">${esc(it.title)}</div>
                    <div class="row-sub">${esc(it.artist)}</div>
                  </div>
                  <div class="row-dur">${fmtTime(dur)}</div>
                  <button class="q-x" hx-post="/api/queue/remove/${it.index}" hx-target="#qp-body" hx-swap="innerHTML" aria-label="Remove from queue">
                    <svg width="12" height="12" viewBox="0 0 12 12"><path d="M1 1 L11 11 M11 1 L1 11" stroke="currentColor" stroke-width="2" stroke-linecap="round"></path></svg>
                  </button>
                  <div class="q-grab" aria-label="Drag to reorder"><span></span><span></span><span></span></div>
                </div>"""
            }.joinToString("")
            """
            <div class="qp-label" style="margin-top:26px;"><span>Next up</span><span class="qp-meta">$meta</span></div>
            <div id="qp-list" data-base="${cur.index + 1}">$rows</div>"""
        }

        return """<div id="qp-state" data-track-id="${cur.trackId}" hidden></div>$nowCard$nextUp"""
    }
}
