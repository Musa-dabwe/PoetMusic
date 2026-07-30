package com.musa.poetmusic.server

import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.data.Track

/**
 * Queue context: identifies which listing a row was tapped in, so the play
 * queue can be rebuilt to match what the user sees.
 */
data class QueueCtx(
    val ctx: String = "songs",
    val q: String = "",
    val sort: String = "title",
    val album: String = "",
    val artist: String = "",
    val pid: Long = 0
) {
    fun resolve(db: MusicDatabase): List<Track> = when (ctx) {
        "album" -> db.tracksForAlbum(album, artist)
        "artist" -> db.tracksForArtist(artist)
        "playlist" -> db.playlistTracks(pid)
        "favorites" -> db.favorites()
        else -> db.tracks(q, sort)
    }

    fun query(): String = buildString {
        append("ctx=").append(enc(ctx))
        if (q.isNotEmpty()) append("&q=").append(enc(q))
        if (sort != "title") append("&sort=").append(enc(sort))
        if (album.isNotEmpty()) append("&album=").append(enc(album))
        if (artist.isNotEmpty()) append("&artist=").append(enc(artist))
        if (pid != 0L) append("&pid=").append(pid)
    }
}

/** Building blocks shared by the library, detail and drawer views. */
object SharedViews {

    /** Check glyph shown inside a selected row's checkbox. */
    internal val CHECK_SVG =
        """<svg width="12" height="10" viewBox="0 0 12 10"><path d="M1 5 L4.5 8.5 L11 1" stroke="#3b3651" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"></path></svg>"""

    /**
     * A library song tile. Tap plays the track; long-press / right-click enters
     * multi-select (checkbox + Contextual Action Bar); the ⋯ button opens the
     * full options drawer. Tap-to-play and select-toggle are handled by the
     * delegated row click handler in Shell using the data-* URLs below.
     */
    fun songRow(t: Track, ctx: QueueCtx): String {
        val cq = ctx.query()
        val art =
            if (t.hasArt) """<img loading="lazy" src="/api/art/${t.id}?v=${t.lastModified}" alt="">"""
            else esc(initials(t.title))
        val fav = if (t.favorite) """ <span style="color:var(--accent);">♥</span>""" else ""
        return """
        <div class="row" data-track-id="${t.id}" data-cq="${esc(cq)}"
             data-play-url="${esc("/api/player/play/${t.id}?$cq")}"
             data-drawer-url="${esc("/api/library/drawer?ids=${t.id}&$cq")}">
          <div class="row-check">$CHECK_SVG</div>
          <div class="row-art" style="background:${artColor(t.id)};">$art</div>
          <div class="row-main">
            <div class="row-title">${esc(t.title)}$fav</div>
            <div class="row-sub">${esc(t.artist)}</div>
          </div>
          <div class="row-dur">${fmtTime(t.durationMs)}</div>
          <button class="row-menu-btn" aria-label="Track options">⋯</button>
        </div>"""
    }

    fun songList(tracks: List<Track>, ctx: QueueCtx): String =
        if (tracks.isEmpty()) """<div class="empty">No songs here yet.</div>"""
        else """<div style="display:flex; flex-direction:column; gap:6px;">${tracks.joinToString("") { songRow(it, ctx) }}</div>"""

    internal fun masterControls(ctx: QueueCtx): String {
        val cq = ctx.query()
        return """
        <div style="display:flex; gap:10px; margin-bottom:18px;">
          <button class="btn-primary" hx-post="/api/queue/replace?${esc(cq)}&shuffle=0" hx-swap="none">
            <svg width="12" height="14" viewBox="0 0 12 14"><polygon points="0,0 12,7 0,14" fill="#3b3651"></polygon></svg>
            Play all
          </button>
          <button class="btn-outline" hx-post="/api/queue/replace?${esc(cq)}&shuffle=1" hx-swap="none">
            <span style="font-size:16px; line-height:1;">⇆</span> Shuffle all
          </button>
        </div>"""
    }
}
