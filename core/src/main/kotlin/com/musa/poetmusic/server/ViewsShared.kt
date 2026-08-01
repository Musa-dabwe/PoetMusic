package com.musa.poetmusic.server

import com.musa.poetmusic.data.LibraryStore
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
    val pid: Long = 0,
    val genre: String = ""
) {
    fun resolve(db: LibraryStore): List<Track> = when (ctx) {
        "album" -> db.tracksForAlbum(album, artist)
        "artist" -> db.tracksForArtist(artist)
        "genre" -> db.tracksForGenre(genre)
        "playlist" -> db.playlistTracks(pid)
        "favorites" -> db.favorites()
        else -> db.tracks(q, sort)
    }

    /**
     * One page of the listing. The songs tab windows in SQL because it spans
     * the whole library; every other context is already bounded by an album,
     * artist, genre or playlist, so it is sliced after the read — the point of
     * both paths is the same, keeping the rendered row count bounded.
     */
    fun resolvePage(db: LibraryStore, offset: Int, limit: Int): List<Track> =
        if (ctx == "songs") db.tracks(q, sort, limit, offset)
        else resolve(db).drop(offset).take(limit)

    /** Total rows behind [resolvePage], for the "showing x of y" footer. */
    fun total(db: LibraryStore): Int =
        if (ctx == "songs") db.trackCount(q) else resolve(db).size

    fun query(): String = buildString {
        append("ctx=").append(enc(ctx))
        if (q.isNotEmpty()) append("&q=").append(enc(q))
        if (sort != "title") append("&sort=").append(enc(sort))
        if (album.isNotEmpty()) append("&album=").append(enc(album))
        if (artist.isNotEmpty()) append("&artist=").append(enc(artist))
        if (pid != 0L) append("&pid=").append(pid)
        if (genre.isNotEmpty()) append("&genre=").append(enc(genre))
    }
}

/** Building blocks shared by the library, detail and drawer views. */
object SharedViews {

    /** Check glyph shown inside a selected row's checkbox. */
    internal val CHECK_SVG =
        """<svg width="12" height="10" viewBox="0 0 12 10"><path d="M1 5 L4.5 8.5 L11 1" stroke="#3b3651" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"></path></svg>"""

    /**
     * A library song tile. Tap plays the track; a 300 ms press enters
     * multi-select (checkbox + Contextual Action Bar); the options drawer is a
     * right-click where there is a mouse, and the ⋯ button where there is not.
     * Both affordances are always in the markup — `poet.css` hides ⋯ on
     * `(pointer: fine)`, so which one shows is a CSS decision like every other
     * mode difference. Tap-to-play, select-toggle and the drawer are handled by
     * the delegated handlers in poet.js using the data-* URLs below.
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

    /**
     * How many rows one page of a tracklist renders. Server-rendering every
     * row of a 10k-track library on each keystroke is what this bounds; the
     * rest arrive through the "Load more" sentinel below.
     */
    const val PAGE_SIZE = 60

    /**
     * A tracklist page. [total] is the full row count behind it, so the
     * sentinel knows whether more rows exist; pass the list's own size (the
     * default) for listings that are rendered whole.
     */
    fun songList(tracks: List<Track>, ctx: QueueCtx, offset: Int = 0, total: Int = tracks.size): String {
        if (tracks.isEmpty() && offset == 0) return """<div class="empty">No songs here yet.</div>"""
        val rows = tracks.joinToString("") { songRow(it, ctx) }
        return """<div style="display:flex; flex-direction:column; gap:6px;">$rows${loadMore(ctx, offset + tracks.size, total)}</div>"""
    }

    /**
     * Rows for the next page, swapped in place of the sentinel that requested
     * them (hx-swap="outerHTML"), so each tap appends a page and re-arms the
     * button without re-rendering what is already on screen.
     */
    fun songListPage(tracks: List<Track>, ctx: QueueCtx, offset: Int, total: Int): String =
        tracks.joinToString("") { songRow(it, ctx) } + loadMore(ctx, offset + tracks.size, total)

    /** The "Load more" sentinel, or nothing once the whole listing is rendered. */
    private fun loadMore(ctx: QueueCtx, shown: Int, total: Int): String {
        if (shown >= total) return ""
        val remaining = total - shown
        return """
        <button class="load-more" hx-get="/partial/songs-page?offset=$shown&amp;${esc(ctx.query())}"
                hx-target="this" hx-swap="outerHTML">
          Load more <span class="load-more-count">$remaining left</span>
        </button>"""
    }

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
