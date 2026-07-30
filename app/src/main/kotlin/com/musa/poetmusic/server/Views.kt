package com.musa.poetmusic.server

import com.musa.poetmusic.data.LibraryScanner
import com.musa.poetmusic.data.LyricLine
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.data.Playlist
import com.musa.poetmusic.data.TagEditor
import com.musa.poetmusic.data.Track
import com.musa.poetmusic.playback.PlayerController

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

object Views {

    private fun speedLabel(speed: Float): String =
        listOf(0.75f to "0.75", 1f to "1.0", 1.25f to "1.25", 1.5f to "1.5", 2f to "2.0")
            .firstOrNull { kotlin.math.abs(it.first - speed) < 0.01f }?.second
            ?: speed.toString()

    // ---------------- shared pieces ----------------

    /** Check glyph shown inside a selected row's checkbox. */
    private val CHECK_SVG =
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

    private fun masterControls(ctx: QueueCtx): String {
        val cq = ctx.query()
        return """
        <div style="display:flex; gap:10px; margin-bottom:18px;">
          <button class="btn-primary" hx-post="/api/queue/replace?$cq&shuffle=0" hx-swap="none">
            <svg width="12" height="14" viewBox="0 0 12 14"><polygon points="0,0 12,7 0,14" fill="#3b3651"></polygon></svg>
            Play all
          </button>
          <button class="btn-outline" hx-post="/api/queue/replace?$cq&shuffle=1" hx-swap="none">
            <span style="font-size:16px; line-height:1;">⇆</span> Shuffle all
          </button>
        </div>"""
    }

    // ---------------- library ----------------

    fun libraryScreen(db: MusicDatabase, tab: String, q: String, sort: String): String {
        val tabs = listOf("Songs", "Albums", "Artists", "Playlists")
        val tabBtns = tabs.joinToString("") { name ->
            val active = if (name.lowercase() == tab) " active" else ""
            """<button class="pill$active" hx-get="/screens/library?tab=${name.lowercase()}" hx-target="#main-container">$name</button>"""
        }
        val body = when (tab) {
            "albums" -> albumsTab(db)
            "artists" -> artistsTab(db)
            "playlists" -> playlistsTab(db)
            else -> songsTab(db, q, sort)
        }
        return """
        <div class="screen" data-screen="library">
          <div style="display:flex; gap:6px; margin-bottom:16px; flex-wrap:wrap;">$tabBtns</div>
          $body
        </div>"""
    }

    private fun songsTab(db: MusicDatabase, q: String, sort: String): String {
        val ctx = QueueCtx("songs", q, sort)
        val tracks = db.tracks(q, sort)
        if (tracks.isEmpty() && q.isEmpty()) {
            return """
            <div class="empty">
              <div style="font-size:40px; margin-bottom:12px;">♪</div>
              Your library is empty.<br>Add a music folder in Settings, then run a scan.
              <div style="margin-top:16px;"><button class="btn-primary" hx-get="/screens/settings" hx-target="#main-container">Open Settings</button></div>
            </div>"""
        }
        return """
        ${masterControls(ctx)}
        <div class="searchrow">
          <input id="q" name="q" type="search" placeholder="Search songs, artists, albums…" value="${esc(q)}"
                 hx-get="/partial/songs" hx-trigger="input changed delay:300ms, search" hx-target="#song-list" hx-swap="innerHTML">
          <button class="sortbtn" hx-get="/partial/sort-drawer" hx-target="#sheet-root" hx-swap="innerHTML" aria-label="Sort songs">
            <svg width="14" height="12" viewBox="0 0 14 12"><rect x="0" y="1" width="14" height="2" rx="1" fill="currentColor"></rect><rect x="0" y="5" width="10" height="2" rx="1" fill="currentColor"></rect><rect x="0" y="9" width="6" height="2" rx="1" fill="currentColor"></rect></svg>
            Sort
          </button>
        </div>
        <div id="song-list">${songList(tracks, ctx)}</div>"""
    }

    /** The five sort states exposed by the pastel sort drawer, in display order. */
    val SORT_STATES = listOf(
        Triple("title-az", "title", "Title A-Z"),
        Triple("title-za", "title_desc", "Title Z-A"),
        Triple("artist-az", "artist", "Artist A-Z"),
        Triple("artist-za", "artist_desc", "Artist Z-A"),
        Triple("date-modified", "date_modified", "Date modified")
    )

    /**
     * Touch-friendly bottom drawer replacing the native select: custom radio
     * indicators, each option fires hx-get /api/library/sort?type=… at the
     * tracklist container.
     */
    fun sortDrawer(currentSort: String): String {
        val options = SORT_STATES.joinToString("") { (type, key, label) ->
            val selected = key == currentSort
            """
            <button class="sortopt${if (selected) " active" else ""}"
                    hx-get="/api/library/sort?type=$type" hx-include="#q"
                    hx-target="#song-list" hx-swap="innerHTML"
                    hx-on::after-request="closeSheet()">
              <span class="radio">${if (selected) """<span class="radio-dot"></span>""" else ""}</span>
              <span style="font-size:14px; font-weight:600;">$label</span>
            </button>"""
        }
        return """
        <div class="sheet-shield" onclick="closeSheet()"></div>
        <div class="sheet">
          <div class="sheet-grab"></div>
          <div class="sheet-title" style="margin-bottom:14px;">Sort songs by</div>
          <div style="display:flex; flex-direction:column; gap:6px;">$options</div>
        </div>"""
    }

    private fun albumsTab(db: MusicDatabase): String {
        val albums = db.albums()
        if (albums.isEmpty()) return """<div class="empty">No albums yet.</div>"""
        val cards = albums.joinToString("") { a ->
            """
            <div class="album-card" hx-get="/screens/album?album=${enc(a.album)}&artist=${enc(a.artist)}" hx-target="#main-container">
              <div class="album-art" style="background:${artColor(a.artTrackId)};"><img loading="lazy" src="/api/art/${a.artTrackId}" alt="" onerror="this.remove()"></div>
              <div style="font-size:14px; font-weight:600; margin-top:8px;">${esc(a.album)}</div>
              <div style="font-size:12px; color:var(--muted);">${esc(a.artist)} · ${a.trackCount} songs</div>
            </div>"""
        }
        return """<div class="grid">$cards</div>"""
    }

    private fun artistsTab(db: MusicDatabase): String {
        val artists = db.artists()
        if (artists.isEmpty()) return """<div class="empty">No artists yet.</div>"""
        val rows = artists.joinToString("") { a ->
            """
            <div class="row" hx-get="/screens/artist?name=${enc(a.artist)}" hx-target="#main-container">
              <div class="row-art" style="border-radius:50%; background:${artColor(a.artTrackId)};">${esc(initials(a.artist))}</div>
              <div class="row-main">
                <div class="row-title">${esc(a.artist)}</div>
                <div class="row-sub">${a.trackCount} songs</div>
              </div>
            </div>"""
        }
        return """<div style="display:flex; flex-direction:column; gap:6px;">$rows</div>"""
    }

    private fun playlistsTab(db: MusicDatabase): String {
        val favCount = db.favorites().size
        val favRow = """
        <div class="row" hx-get="/screens/favorites" hx-target="#main-container">
          <div class="row-art" style="background:#f7dce8; font-size:16px;">♥</div>
          <div class="row-main">
            <div class="row-title">Favorites</div>
            <div class="row-sub">$favCount songs</div>
          </div>
        </div>"""
        val rows = db.playlists().joinToString("") { p ->
            """
            <div class="row" hx-get="/screens/playlist/${p.id}" hx-target="#main-container">
              <div class="row-art" style="background:${artColor(p.id)}; font-size:16px;">♪</div>
              <div class="row-main">
                <div class="row-title">${esc(p.name)}</div>
                <div class="row-sub">${p.trackCount} songs</div>
              </div>
            </div>"""
        }
        return """
        <div style="display:flex; flex-direction:column; gap:6px;">
          $favRow
          $rows
        </div>
        <form style="display:flex; gap:8px; margin-top:16px;" hx-post="/api/playlist/create" hx-swap="none" hx-on::after-request="this.reset()">
          <input name="name" placeholder="New playlist name" required
                 style="flex:1; min-width:0; border:none; border-radius:12px; padding:10px 14px; font-family:inherit; font-size:14px; color:var(--ink); background:var(--overlay-neutral);">
          <button type="submit" class="btn-primary">＋ Create</button>
        </form>"""
    }

    // ---------------- detail screens ----------------

    fun albumScreen(db: MusicDatabase, album: String, artist: String): String {
        val ctx = QueueCtx("album", album = album, artist = artist)
        val tracks = ctx.resolve(db)
        val artId = tracks.firstOrNull()?.id ?: 0
        return """
        <div class="screen">
          <button class="backlink" hx-get="/screens/library?tab=albums" hx-target="#main-container">← Albums</button>
          <div style="display:flex; align-items:center; gap:16px; margin-bottom:18px;">
            <div class="album-art" style="width:96px; background:${artColor(artId)};"><img src="/api/art/$artId" alt="" onerror="this.remove()"></div>
            <div>
              <div style="font-size:20px; font-weight:700; letter-spacing:-0.02em;">${esc(album)}</div>
              <div style="font-size:13px; color:var(--muted);">${esc(artist)} · ${tracks.size} songs</div>
            </div>
          </div>
          ${masterControls(ctx)}
          ${songList(tracks, ctx)}
        </div>"""
    }

    fun artistScreen(db: MusicDatabase, name: String): String {
        val ctx = QueueCtx("artist", artist = name)
        val tracks = ctx.resolve(db)
        return """
        <div class="screen">
          <button class="backlink" hx-get="/screens/library?tab=artists" hx-target="#main-container">← Artists</button>
          <div style="font-size:20px; font-weight:700; letter-spacing:-0.02em; margin-bottom:4px;">${esc(name)}</div>
          <div style="font-size:13px; color:var(--muted); margin-bottom:16px;">${tracks.size} songs</div>
          ${masterControls(ctx)}
          ${songList(tracks, ctx)}
        </div>"""
    }

    fun favoritesScreen(db: MusicDatabase): String {
        val ctx = QueueCtx("favorites")
        val tracks = ctx.resolve(db)
        return """
        <div class="screen">
          <button class="backlink" hx-get="/screens/library?tab=playlists" hx-target="#main-container">← Playlists</button>
          <div style="font-size:20px; font-weight:700; letter-spacing:-0.02em; margin-bottom:4px;">Favorites ♥</div>
          <div style="font-size:13px; color:var(--muted); margin-bottom:16px;">${tracks.size} songs</div>
          ${if (tracks.isNotEmpty()) masterControls(ctx) else ""}
          ${songList(tracks, ctx)}
        </div>"""
    }

    fun playlistScreen(db: MusicDatabase, pid: Long): String {
        val pl = db.playlist(pid) ?: return """<div class="empty">Playlist not found.</div>"""
        val ctx = QueueCtx("playlist", pid = pid)
        val tracks = ctx.resolve(db)
        return """
        <div class="screen">
          <button class="backlink" hx-get="/screens/library?tab=playlists" hx-target="#main-container">← Playlists</button>
          <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:4px;">
            <div style="font-size:20px; font-weight:700; letter-spacing:-0.02em;">${esc(pl.name)}</div>
            <button class="pill" hx-get="/partial/confirm-playlist/${pl.id}" hx-target="#modal-root" hx-swap="innerHTML">Delete</button>
          </div>
          <div style="font-size:13px; color:var(--muted); margin-bottom:16px;">${tracks.size} songs</div>
          ${if (tracks.isNotEmpty()) masterControls(ctx) else ""}
          ${songList(tracks, ctx)}
        </div>"""
    }

    // ---------------- options drawer + sub-sheets ----------------

    // Drawer action icons (pastel line-art matching the Poet design).
    private val ICON_D_PLAY = """<svg width="12" height="14" viewBox="0 0 12 14"><polygon points="0,0 12,7 0,14" fill="#3b3651"></polygon></svg>"""
    private val ICON_D_NEXT = """<svg width="18" height="14" viewBox="0 0 18 14"><rect x="0" y="1" width="10" height="2" rx="1" fill="#3b3651"></rect><rect x="0" y="6" width="10" height="2" rx="1" fill="#3b3651"></rect><rect x="0" y="11" width="7" height="2" rx="1" fill="#3b3651"></rect><polygon points="13,4 18,7 13,10" fill="#3b3651"></polygon></svg>"""
    private val ICON_D_QUEUE = """<svg width="16" height="14" viewBox="0 0 16 14"><rect x="0" y="1" width="12" height="2" rx="1" fill="#3b3651"></rect><rect x="0" y="6" width="12" height="2" rx="1" fill="#3b3651"></rect><rect x="0" y="11" width="8" height="2" rx="1" fill="#3b3651"></rect></svg>"""
    private val ICON_D_PLAYLIST = """<svg width="16" height="16" viewBox="0 0 16 16"><path d="M2 4 H14 M2 8 H10 M2 12 H10 M13 9 V15 M10 12 H16" stroke="#3b3651" stroke-width="2" fill="none" stroke-linecap="round"></path></svg>"""
    private val ICON_D_DELETE = """<svg width="14" height="16" viewBox="0 0 14 16"><path d="M2 4 H12 M5 4 V2 H9 V4 M3 4 L3.7 14 H10.3 L11 4" stroke="#c25f6e" stroke-width="1.6" fill="none" stroke-linecap="round" stroke-linejoin="round"></path></svg>"""
    private val ICON_D_TAGS = """<svg width="16" height="16" viewBox="0 0 16 16"><path d="M11 2 L14 5 L5 14 L2 14 L2 11 Z" stroke="#3b3651" stroke-width="1.6" fill="none" stroke-linejoin="round"></path></svg>"""
    private val ICON_D_SETAS = """<svg width="16" height="16" viewBox="0 0 16 16"><path d="M8 1 L10 6 L15 6 L11 9 L12.5 14 L8 11 L3.5 14 L5 9 L1 6 L6 6 Z" stroke="#3b3651" stroke-width="1.3" fill="none" stroke-linejoin="round"></path></svg>"""
    private val ICON_D_SHARE = """<svg width="16" height="16" viewBox="0 0 16 16"><circle cx="12" cy="3" r="2" stroke="#3b3651" stroke-width="1.5" fill="none"></circle><circle cx="4" cy="8" r="2" stroke="#3b3651" stroke-width="1.5" fill="none"></circle><circle cx="12" cy="13" r="2" stroke="#3b3651" stroke-width="1.5" fill="none"></circle><path d="M5.7 7 L10.3 4 M5.7 9 L10.3 12" stroke="#3b3651" stroke-width="1.5"></path></svg>"""
    private val ICON_D_ALBUM = """<svg width="16" height="16" viewBox="0 0 16 16"><rect x="1.5" y="1.5" width="13" height="13" rx="3" stroke="#3b3651" stroke-width="1.5" fill="none"></rect><circle cx="8" cy="8" r="2" stroke="#3b3651" stroke-width="1.5" fill="none"></circle></svg>"""
    private val ICON_D_ARTIST = """<svg width="16" height="16" viewBox="0 0 16 16"><circle cx="8" cy="5" r="3" stroke="#3b3651" stroke-width="1.5" fill="none"></circle><path d="M2.5 14 C2.5 10.5 5 9 8 9 C11 9 13.5 10.5 13.5 14" stroke="#3b3651" stroke-width="1.5" fill="none" stroke-linecap="round"></path></svg>"""
    private val ICON_D_FOLDER = """<svg width="16" height="16" viewBox="0 0 16 16"><path d="M1.5 4 L6 4 L7.5 6 L14.5 6 L14.5 13 L1.5 13 Z" stroke="#3b3651" stroke-width="1.5" fill="none" stroke-linejoin="round"></path></svg>"""
    private val ICON_D_INFO = """<svg width="16" height="16" viewBox="0 0 16 16"><circle cx="8" cy="8" r="6.5" stroke="#3b3651" stroke-width="1.5" fill="none"></circle><path d="M8 7 V11.5 M8 4.6 V5" stroke="#3b3651" stroke-width="1.6" stroke-linecap="round"></path></svg>"""
    private val ICON_D_CLOCK = """<svg width="16" height="16" viewBox="0 0 16 16"><circle cx="8" cy="8" r="6.5" stroke="#3b3651" stroke-width="1.5" fill="none"></circle><path d="M8 4.5 V8 L10.5 9.5" stroke="#3b3651" stroke-width="1.5" fill="none" stroke-linecap="round" stroke-linejoin="round"></path></svg>"""
    private val ICON_D_HOURGLASS = """<svg width="16" height="16" viewBox="0 0 16 16"><path d="M4 1.5 H12 M4 14.5 H12 M5 1.5 V4 C5 6 8 7 8 8 C8 9 5 10 5 12 V14.5 M11 1.5 V4 C11 6 8 7 8 8 C8 9 11 10 11 12 V14.5" stroke="#3b3651" stroke-width="1.4" fill="none" stroke-linecap="round" stroke-linejoin="round"></path></svg>"""
    private val ICON_D_TIMER_OFF = """<svg width="16" height="16" viewBox="0 0 16 16"><circle cx="8" cy="8" r="6.5" stroke="#c25f6e" stroke-width="1.5" fill="none"></circle><path d="M3.5 3.5 L12.5 12.5" stroke="#c25f6e" stroke-width="1.5" stroke-linecap="round"></path></svg>"""

    /** One tappable row inside the options drawer. */
    private fun drawerItem(
        icon: String, label: String, attrs: String, sub: String = "", chevron: Boolean = false, danger: Boolean = false
    ): String {
        val labelBlock =
            if (sub.isEmpty()) """<span class="dlabel">$label</span>"""
            else """<span style="flex:1;"><span class="dlabel" style="display:block;">$label</span><span class="dlabel-sub">$sub</span></span>"""
        val chev = if (chevron) """<span class="dchev">›</span>""" else ""
        return """<button class="ditem${if (danger) " ditem-danger" else ""}" $attrs>
          <span class="dicon">$icon</span>$labelBlock$chev
        </button>"""
    }

    /**
     * Full options bottom sheet. Renders the same four grouped sections for a
     * single track and for a batch selection; single-only items (edit tags,
     * navigation) are hidden in batch mode. `ids` is the comma-separated target
     * list carried into every follow-up action; `cq` re-supplies the queue
     * context for play/navigation.
     */
    fun optionsDrawer(db: MusicDatabase, tracks: List<Track>, ids: String, ctx: QueueCtx): String {
        val single = tracks.size == 1
        val first = tracks.first()
        val cq = ctx.query()
        val q = "ids=$ids&$cq"
        val headArt =
            if (single && first.hasArt) """<img src="/api/art/${first.id}?v=${first.lastModified}" alt="">"""
            else if (single) esc(initials(first.title)) else tracks.size.toString()
        val headBg = if (single) artColor(first.id) else "var(--accent-faint)"
        val title = if (single) esc(first.title) else "${tracks.size} tracks selected"
        val sub = if (single) esc(first.artist) else "Batch actions"

        // Playlist & file
        val removeFromPlaylist =
            if (single && ctx.ctx == "playlist" && ctx.pid != 0L)
                drawerItem(ICON_D_PLAYLIST, "Remove from this playlist",
                    """hx-post="/api/playlist/${ctx.pid}/remove/${first.id}" hx-swap="none" hx-on::after-request="poetFinish()"""")
            else ""

        val editTags = if (single) drawerItem(ICON_D_TAGS, "Edit tags",
            """hx-get="/api/library/edit-tags/${first.id}" hx-target="#modal-root" hx-swap="innerHTML" hx-on::after-request="poetFinish()"""") else ""

        val navSection = if (single) """
          <div class="dsec">Navigation &amp; info</div>
          ${drawerItem(ICON_D_ALBUM, "Go to album",
            """hx-get="/screens/album?album=${enc(first.album)}&artist=${enc(first.artist)}" hx-target="#main-container" hx-on::after-request="poetFinish()"""")}
          ${drawerItem(ICON_D_ARTIST, "Go to artist",
            """hx-get="/screens/artist?name=${enc(first.artist)}" hx-target="#main-container" hx-on::after-request="poetFinish()"""")}
          ${drawerItem(ICON_D_FOLDER, "Go to folder", """onclick="poetToast('Opening folder…'); poetFinish();"""")}
          ${drawerItem(ICON_D_INFO, "Song info / details",
            """hx-get="/api/library/sub?kind=info&$q" hx-target="#sheet-root" hx-swap="innerHTML"""")}
        """ else ""

        return """
        <div class="sheet-shield" onclick="poetDrawerClose()"></div>
        <div class="drawer">
          <div class="sheet-grab"></div>
          <div class="drawer-head">
            <div class="drawer-head-art" style="background:$headBg;">$headArt</div>
            <div style="flex:1; min-width:0;">
              <div style="font-size:15px; font-weight:700; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">$title</div>
              <div style="font-size:12px; color:var(--muted); white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">$sub</div>
            </div>
          </div>

          <div class="dsec">Playback &amp; queue</div>
          ${drawerItem(ICON_D_PLAY, "Play now", """hx-post="/api/tracks/play-now?$q" hx-swap="none" hx-on::after-request="poetFinish()"""")}
          ${drawerItem(ICON_D_NEXT, "Play next", """hx-post="/api/tracks/play-next?$q" hx-swap="none" hx-on::after-request="poetFinish()"""")}
          ${drawerItem(ICON_D_QUEUE, "Add to queue", """hx-post="/api/tracks/add-queue?$q" hx-swap="none" hx-on::after-request="poetFinish()"""")}

          <div class="dsec">Playlist &amp; file</div>
          ${drawerItem(ICON_D_PLAYLIST, "Add to playlist",
            """hx-get="/api/library/sub?kind=addplaylist&$q" hx-target="#sheet-root" hx-swap="innerHTML"""")}
          $removeFromPlaylist
          ${if (ctx.ctx == "playlist") "" else drawerItem(ICON_D_DELETE, "Delete from device",
            """hx-get="/api/library/sub?kind=delete&$q" hx-target="#sheet-root" hx-swap="innerHTML"""", danger = true)}

          <div class="dsec">Metadata &amp; utilities</div>
          $editTags
          ${drawerItem(ICON_D_SETAS, "Set as…",
            """hx-get="/api/library/sub?kind=setas&$q" hx-target="#sheet-root" hx-swap="innerHTML"""",
            sub = "Ringtone, notification, alarm", chevron = true)}
          ${drawerItem(ICON_D_SHARE, "Share", """onclick="poetToast('Opening share sheet…'); poetFinish();"""")}

          $navSection
        </div>"""
    }

    /** Dispatches a drawer sub-sheet by kind. */
    fun subSheet(kind: String, db: MusicDatabase, tracks: List<Track>, ids: String, ctx: QueueCtx, infoSizeBytes: Long = -1): String {
        val cq = ctx.query()
        val back = "poetSubBack('$ids','${esc(cq)}')"
        return when (kind) {
            "addplaylist" -> addPlaylistSheet(db, tracks, ids, back)
            "setas" -> setAsSheet(back)
            "info" -> songInfoSheet(tracks.first(), infoSizeBytes, back)
            "delete" -> deleteConfirmSheet(tracks, ids, back)
            else -> ""
        }
    }

    private fun targetLabel(tracks: List<Track>): String =
        if (tracks.size == 1) "“${esc(tracks.first().title)}”"
        else "${tracks.size} songs"

    private fun addPlaylistSheet(db: MusicDatabase, tracks: List<Track>, ids: String, back: String): String {
        val picks = db.playlists().joinToString("") { p ->
            """
            <button class="pl-pick" data-pid="${p.id}" onclick="poetPlToggle(this)">
              <span class="pl-check">$CHECK_SVG</span>
              <span class="pl-art" style="background:${artColor(p.id)};">♪</span>
              <span style="flex:1;"><span style="font-size:14px; font-weight:600; display:block;">${esc(p.name)}</span><span style="font-size:12px; color:var(--muted);">${p.trackCount} songs</span></span>
            </button>"""
        }.ifBlank { """<div style="font-size:12px; color:var(--muted); padding:6px 2px;">No playlists yet — create one below.</div>""" }
        return """
        <div class="sheet-shield" onclick="$back"></div>
        <div class="sheet sheet-tall">
          <div class="sheet-grab"></div>
          <div class="sheet-title" style="margin-bottom:4px;">Add to playlist</div>
          <div style="font-size:12px; color:var(--muted); margin-bottom:14px;">${targetLabel(tracks)}</div>
          <form class="pl-create" hx-post="/api/playlist/create?ids=$ids" hx-swap="none"
                hx-on::after-request="poetFinish()">
            <input name="name" placeholder="Create new playlist…" required>
            <button type="submit">Create</button>
          </form>
          <div style="display:flex; flex-direction:column; gap:4px;">$picks</div>
          <button class="sheet-btn" onclick="poetAddPlaylists('$ids')">Done</button>
        </div>"""
    }

    private fun setAsSheet(back: String): String {
        val opts = listOf("Ringtone", "Notification sound", "Alarm").joinToString("") { x ->
            """<button class="setas-opt" onclick="poetToast('Set as ${x.lowercase()}'); poetFinish();">$x</button>"""
        }
        return """
        <div class="sheet-shield" onclick="$back"></div>
        <div class="sheet">
          <div class="sheet-grab"></div>
          <div class="sheet-title" style="margin-bottom:12px;">Set track as</div>
          <div style="display:flex; flex-direction:column; gap:8px;">$opts</div>
        </div>"""
    }

    /** Human-readable file size for the info sheet ("weight" of the track). */
    private fun fmtSize(bytes: Long): String = when {
        bytes < 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    private fun songInfoSheet(t: Track, sizeBytes: Long, back: String): String {
        val ext = t.displayName.substringAfterLast('.', "").uppercase().ifBlank { "—" }
        val added = if (t.dateAdded > 0)
            java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(java.util.Date(t.dateAdded)) else "—"
        val rows = listOf(
            "Title" to t.title,
            "Artist" to t.artist.ifBlank { "—" },
            "File name" to t.displayName,
            "Format" to ext,
            "File size" to fmtSize(sizeBytes),
            "Duration" to fmtTime(t.durationMs),
            "Album" to t.album.ifBlank { "—" },
            "Track / disc" to "${if (t.trackNo > 0) t.trackNo else "—"} / ${if (t.discNo > 0) t.discNo else "—"}",
            "Year" to t.year.ifBlank { "—" },
            "Date added" to added
        ).joinToString("") { (k, v) ->
            """<div class="info-row"><span class="info-k">$k</span><span class="info-v">${esc(v)}</span></div>"""
        }
        return """
        <div class="center-shield" onclick="if(event.target===this) $back">
          <div class="info-card">
            <div style="font-size:16px; font-weight:700; margin-bottom:2px;">${esc(t.title)}</div>
            <div style="font-size:12px; color:var(--muted); margin-bottom:16px;">${esc(t.artist)}</div>
            <div style="display:flex; flex-direction:column; gap:10px;">$rows</div>
            <button class="btn-primary" style="width:100%; justify-content:center; margin-top:18px;" onclick="$back">Close</button>
          </div>
        </div>"""
    }

    private fun deleteConfirmSheet(tracks: List<Track>, ids: String, back: String): String {
        val msg =
            if (tracks.size == 1) "Delete “${esc(tracks.first().title)}”?"
            else "Delete ${tracks.size} songs?"
        return """
        <div class="center-shield" onclick="if(event.target===this) $back">
          <div class="confirm-card">
            <div class="confirm-title">Delete from device</div>
            <div class="confirm-msg">$msg This permanently deletes the file${if (tracks.size == 1) "" else "s"} from your device.</div>
            <div class="confirm-actions">
              <button class="btn-ghost" onclick="$back">CANCEL</button>
              <button class="btn-delete" hx-post="/api/tracks/delete?ids=$ids" hx-swap="none"
                      hx-on::after-request="poetFinish()">DELETE</button>
            </div>
          </div>
        </div>"""
    }

    // ---------------- native-fidelity confirmation modal ----------------

    /**
     * Pastel replacement for the WebView's "http://127.0.0.1 says" confirm()
     * dialog: rounded 18px card over a blurred dim, borderless CANCEL that
     * swaps an empty fragment into #modal-root, filled accent OK.
     */
    private fun confirmModal(title: String, messageHtml: String, okAttrs: String): String = """
        <div class="modal-shield" onclick="if(event.target===this) document.getElementById('modal-root').innerHTML=''">
          <div class="confirm-card">
            <div class="confirm-title">$title</div>
            <div class="confirm-msg">$messageHtml</div>
            <div class="confirm-actions">
              <button class="btn-ghost" hx-get="/partial/empty" hx-target="#modal-root" hx-swap="innerHTML">CANCEL</button>
              <button class="btn-primary confirm-ok" $okAttrs hx-swap="none"
                      hx-on::after-request="document.getElementById('modal-root').innerHTML=''">OK</button>
            </div>
          </div>
        </div>"""

    fun confirmRemoveFolder(id: Long, displayPath: String): String {
        val folderName = displayPath.trimEnd('/').substringAfterLast('/').ifBlank { displayPath }
        return confirmModal(
            "Remove folder",
            """Are you sure you want to remove <span class="confirm-strong">${esc(folderName)}</span> from the library?""",
            """hx-delete="/api/settings/folder?id=$id""""
        )
    }

    fun confirmRemoveTrack(t: Track): String = confirmModal(
        "Remove from library",
        """Remove <span class="confirm-strong">${esc(t.title)}</span> from the library? The file is kept on disk.""",
        """hx-post="/api/track/${t.id}/remove""""
    )

    fun confirmDeletePlaylist(pl: Playlist): String = confirmModal(
        "Delete playlist",
        """Delete the playlist <span class="confirm-strong">${esc(pl.name)}</span>? Its songs stay in the library.""",
        """hx-post="/api/playlist/${pl.id}/delete""""
    )

    // ---------------- tag editor sheet ----------------

    private fun editField(
        label: String, name: String, value: String, numeric: Boolean = false, required: Boolean = false
    ): String = """
        <label class="ed-field">
          <span>$label</span>
          <input type="text" name="$name" value="${esc(value)}"${if (numeric) """ inputmode="numeric"""" else ""}${if (required) " required" else ""}>
        </label>"""

    /**
     * Full-height tag editor sheet (Details / Artwork / Lyrics) matching the
     * Poet design. The header "Save Metadata Changes" bar fires an hx-put form
     * bundle at /api/library/edit-tags/{id}; artwork picking, the rename
     * toggle, the lyrics-mode switch and the synced-LRC maker are driven by the
     * scoped script at the bottom. Non-MP3 files still save to the library.
     */
    fun tagEditorSheet(t: Track, extras: TagEditor.FileExtras, isCurrent: Boolean): String {
        val isMp3 = t.displayName.endsWith(".mp3", ignoreCase = true)
        val ext = t.displayName.substringAfterLast('.', "")
        val artInitials = esc(initials(t.title))
        val comment = extras.comment ?: t.comment
        val embeddedLyrics = extras.lyrics ?: ""
        val artThumb =
            if (t.hasArt) """<img src="/api/art/${t.id}" alt="">"""
            else artInitials
        val saveNote =
            if (isMp3) "Written to the file &amp; re-scanned into the library"
            else "Non-MP3 file — tags are saved to the Poet library only"

        return """
        <div class="sheet-shield" onclick="poetCloseEditor()"></div>
        <form id="ed-form" class="editor-sheet" hx-put="/api/library/edit-tags/${t.id}" hx-swap="none"
              data-track-id="${t.id}" data-ext="${esc(ext)}" data-has-art="${if (t.hasArt) 1 else 0}"
              data-is-current="${if (isCurrent) 1 else 0}">
          <div class="sheet-grab" style="margin:12px auto 12px auto;"></div>

          <div class="ed-head">
            <div class="ed-head-art" id="ed-head-art">$artThumb</div>
            <div style="flex:1; min-width:0;">
              <div class="ed-head-title">Edit tags</div>
              <div class="ed-head-file">${esc(t.displayName)}</div>
            </div>
            <button type="button" class="ed-close" onclick="poetCloseEditor()" aria-label="Close">
              <svg width="13" height="13" viewBox="0 0 12 12"><path d="M1 1 L11 11 M11 1 L1 11" stroke="currentColor" stroke-width="2" stroke-linecap="round"></path></svg>
            </button>
          </div>

          <div class="ed-tabs">
            <button type="button" class="ed-tab active" data-tab="details" onclick="poetEdTab('details')">Details</button>
            <button type="button" class="ed-tab" data-tab="artwork" onclick="poetEdTab('artwork')">Artwork</button>
            <button type="button" class="ed-tab" data-tab="lyrics" onclick="poetEdTab('lyrics')">Lyrics</button>
          </div>

          <div class="ed-body">
            <!-- hidden state carried into the submit -->
            <input type="hidden" name="artAction" id="ed-art-action" value="keep">
            <input type="hidden" name="rename" id="ed-rename-flag" value="0">

            <!-- DETAILS -->
            <div class="ed-pane" data-pane="details">
              <div style="display:flex; flex-direction:column; gap:14px;">
                ${editField("Title", "title", t.title, required = true)}
                ${editField("Artist", "artist", t.artist)}
                ${editField("Album", "album", t.album)}
                ${editField("Album artist", "albumArtist", t.albumArtist)}
                <div style="display:grid; grid-template-columns:1fr 1fr; gap:14px;">
                  ${editField("Genre", "genre", t.genre)}
                  ${editField("Year", "year", t.year, numeric = true)}
                </div>
                <div style="display:grid; grid-template-columns:1fr 1fr; gap:14px;">
                  ${editField("Track no.", "trackNo", if (t.trackNo > 0) t.trackNo.toString() else "", numeric = true)}
                  ${editField("Disc no.", "discNo", if (t.discNo > 0) t.discNo.toString() else "", numeric = true)}
                </div>
                ${editField("Composer", "composer", t.composer)}
                <label class="ed-field">
                  <span>Comment</span>
                  <textarea name="comment" rows="2">${esc(comment)}</textarea>
                </label>

                <div class="ed-rename-card">
                  <div style="display:flex; align-items:center; justify-content:space-between; gap:12px;">
                    <div style="min-width:0;">
                      <div style="font-size:13px; font-weight:700;">Rename file from tags</div>
                      <div style="font-size:11px; color:var(--muted);">Rewrite the physical filename on save</div>
                    </div>
                    <button type="button" class="ed-switch" id="ed-rename-switch" onclick="poetEdToggleRename()" aria-label="Toggle rename">
                      <span class="ed-knob"></span>
                    </button>
                  </div>
                  <div id="ed-rename-detail" hidden style="margin-top:12px;">
                    <input type="text" name="renamePattern" id="ed-rename-pattern" value="%track% - %title%.$ext"
                           class="ed-mono-input" oninput="poetEdRenamePreview()">
                    <div style="font-size:11px; color:var(--muted); margin-top:8px;">Preview</div>
                    <div id="ed-rename-preview" class="ed-rename-preview"></div>
                  </div>
                </div>
              </div>
            </div>

            <!-- ARTWORK -->
            <div class="ed-pane" data-pane="artwork" hidden>
              <div style="display:flex; flex-direction:column; align-items:center; gap:18px;">
                <div class="ed-art-tile" id="ed-art-tile"></div>
                <div style="display:flex; flex-direction:column; gap:8px; width:100%; max-width:300px;">
                  <button type="button" class="btn-primary" style="width:100%; justify-content:center;" onclick="poetEdPickArt()">Choose from gallery</button>
                  <div style="display:flex; gap:8px;">
                    <button type="button" class="ed-art-btn" id="ed-art-remove" onclick="poetEdArt('remove')">Remove artwork</button>
                    <button type="button" class="ed-art-btn" id="ed-art-restore" hidden onclick="poetEdArt('keep')">Restore embedded</button>
                  </div>
                </div>
                <div style="font-size:12px; color:var(--muted); text-align:center; max-width:280px; line-height:1.5;">
                  ${if (isMp3) "Select a local PNG or JPEG. Removing artwork trims the embedded image to save file size."
                    else "Cover-art editing writes into MP3 files only. Other formats keep their existing artwork."}
                </div>
              </div>
            </div>

            <!-- LYRICS -->
            <div class="ed-pane" data-pane="lyrics" hidden>
              <div style="display:flex; flex-direction:column; gap:14px;">
                <div class="ed-seg">
                  <button type="button" class="ed-seg-btn active" data-mode="unsynced" onclick="poetEdLyricMode('unsynced')">Unsynced</button>
                  <button type="button" class="ed-seg-btn" data-mode="synced" onclick="poetEdLyricMode('synced')">Synced (LRC)</button>
                </div>

                <div id="ed-lyric-unsynced">
                  <label class="ed-field">
                    <span>Unsynced lyrics (USLT)</span>
                    <textarea name="lyrics" id="ed-lyrics" rows="12" placeholder="Paste raw lyrics here…"
                              style="line-height:1.6;">${esc(embeddedLyrics)}</textarea>
                  </label>
                </div>

                <div id="ed-lyric-synced" hidden>
                  <div class="ed-lrc-transport">
                    <button type="button" class="ed-lrc-play" id="ed-lrc-play" onclick="poetLrcPlay()" aria-label="Play or pause"></button>
                    <div style="flex:1;">
                      <div class="ed-lrc-clock" id="ed-lrc-clock">00:00.00</div>
                      <div class="ed-lrc-count" id="ed-lrc-count">0 of 0 lines stamped</div>
                    </div>
                    <button type="button" class="ed-lrc-reset" onclick="poetLrcReset()">Reset</button>
                  </div>
                  <button type="button" class="ed-lrc-stamp" onclick="poetLrcStamp()">⌖ Stamp current line</button>
                  <div class="ed-lrc-rows" id="ed-lrc-rows"></div>
                  <button type="button" class="ed-lrc-export" id="ed-lrc-export" onclick="poetLrcExport()">Export .lrc file</button>
                </div>
              </div>
            </div>
          </div>

          <div class="ed-savebar">
            <button type="submit" class="btn-primary" style="width:100%; justify-content:center; padding:15px; font-size:15px;">Save Metadata Changes</button>
            <div style="font-size:11px; color:var(--muted); text-align:center; margin-top:8px;">$saveNote</div>
          </div>
        </form>
        <script>poetInitEditor();</script>"""
    }

    // ---------------- now playing ----------------

    /** Uploaded play-in-order glyph (512-grid trace), recolored via currentColor. */
    private val ICON_PLAY_IN_ORDER = """<svg width="20" height="20" viewBox="0 0 512 512"><path d="M0 0 C141.24 0 282.48 0 428 0 C428 14.52 428 29.04 428 44 C286.76 44 145.52 44 0 44 C0 29.48 0 14.96 0 0 Z " fill="currentColor" transform="translate(42,390)"/><path d="M0 0 C3.8590715 0.96476788 6.07340874 2.10233844 9.41015625 4.17578125 C10.47250488 4.83263916 11.53485352 5.48949707 12.62939453 6.16625977 C13.77386615 6.88150819 14.91823219 7.59692557 16.0625 8.3125 C17.24569307 9.04717693 18.42912638 9.78146707 19.61279297 10.51538086 C27.83053807 15.61756787 36.01510561 20.7725153 44.18359375 25.953125 C49.67013324 29.42988014 55.17820548 32.87195309 60.6875 36.3125 C62.20174561 37.25814819 62.20174561 37.25814819 63.74658203 38.22290039 C65.80412772 39.5076015 67.86174532 40.79218745 69.91943359 42.07666016 C74.94746487 45.21589223 79.97365341 48.35807151 85 51.5 C86.99999085 52.75001464 88.99999086 54.00001462 91 55.25 C103 62.75 115 70.25 127 77.75 C128.48753784 78.6795752 128.48753784 78.6795752 130.00512695 79.62792969 C131.98836694 80.86763656 133.97128171 82.10786388 135.95385742 83.34863281 C141.20762148 86.63564309 146.46810942 89.91122582 151.73828125 93.171875 C153.38860352 94.19571289 153.38860352 94.19571289 155.07226562 95.24023438 C157.18739593 96.55215064 159.30449951 97.86089236 161.42382812 99.16601562 C162.37322266 99.75576172 163.32261719 100.34550781 164.30078125 100.953125 C165.13778564 101.47036133 165.97479004 101.98759766 166.8371582 102.52050781 C168.81113979 103.87081015 170.38442439 105.24611464 172 107 C164.72053162 112.24458839 157.22750617 117.02906084 149.5625 121.6875 C147.24236261 123.10493207 144.92383557 124.52498678 142.60546875 125.9453125 C142.03483704 126.29459305 141.46420532 126.6438736 140.87628174 127.0037384 C136.01596749 129.98190653 131.18796188 133.00856359 126.375 136.0625 C119.37021169 140.5051139 112.3480813 144.91878907 105.3125 149.3125 C104.30300293 149.94293213 103.29350586 150.57336426 102.25341797 151.22290039 C100.19587228 152.5076015 98.13825468 153.79218745 96.08056641 155.07666016 C91.05253513 158.21589223 86.02634659 161.35807151 81 164.5 C79.00000915 165.75001464 77.00000914 167.00001462 75 168.25 C74.01 168.86875 73.02 169.4875 72 170.125 C63 175.75 63 175.75 59.9987793 177.62573242 C58.00281172 178.87323247 56.00688061 180.12079088 54.01098633 181.3684082 C48.94959084 184.53214182 43.88752606 187.69479561 38.82421875 190.85546875 C37.80666504 191.490896 36.78911133 192.12632324 35.74072266 192.78100586 C33.72637207 194.03880721 31.71172983 195.29614162 29.69677734 196.55297852 C24.89703779 199.55012798 20.10798279 202.56216199 15.33984375 205.609375 C14.01690308 206.45302612 14.01690308 206.45302612 12.66723633 207.3137207 C11.01220973 208.37082067 9.35956899 209.43166839 7.7097168 210.49682617 C6.98308838 210.96064697 6.25645996 211.42446777 5.5078125 211.90234375 C4.87504395 212.30960693 4.24227539 212.71687012 3.59033203 213.13647461 C2 214 2 214 0 214 C0 143.38 0 72.76 0 0 Z " fill="currentColor" transform="translate(42,81)"/><path d="M0 0 C70.62 0 141.24 0 214 0 C214 14.52 214 29.04 214 44 C143.38 44 72.76 44 0 44 C0 29.48 0 14.96 0 0 Z " fill="currentColor" transform="translate(256,91)"/><path d="M0 0 C70.62 0 141.24 0 214 0 C214 14.19 214 28.38 214 43 C143.38 43 72.76 43 0 43 C0 28.81 0 14.62 0 0 Z " fill="currentColor" transform="translate(256,241)"/></svg>"""

    /** Uploaded play-single-and-stop glyph (512-grid trace), recolored via currentColor. */
    private val ICON_PLAY_SINGLE_STOP = """<svg width="20" height="20" viewBox="0 0 512 512"><path d="M0 0 C0.78100571 0.72451355 1.56201141 1.4490271 2.36668396 2.19549561 C3.2414035 3.00595337 4.11612305 3.81641113 5.01734924 4.65142822 C9.39795551 8.82383049 13.71296948 13.05623002 17.99339294 17.33123779 C19.3931411 18.72599335 19.3931411 18.72599335 20.82116699 20.14892578 C22.77494405 22.09678124 24.72671185 24.046624 26.67723083 25.9977417 C29.16909121 28.48970458 31.66781974 30.97465965 34.16826534 33.458004 C36.57280917 35.84874947 38.97014543 38.24667642 41.36839294 40.64373779 C42.26211105 41.53027039 43.15582916 42.41680298 44.07662964 43.3302002 C45.31921288 44.57739838 45.31921288 44.57739838 46.5868988 45.84979248 C47.3139653 46.57543396 48.0410318 47.30107544 48.79013062 48.04870605 C52.83748842 52.61563998 54.89266451 57.04032684 54.71604919 63.22186279 C53.02172268 69.10217245 50.55568725 71.79378584 45.71604919 75.22186279 C42.71592487 76.22190423 40.73703622 76.3490518 37.60316086 76.35329056 C36.53906895 76.3571317 35.47497705 76.36097285 34.37863994 76.36493039 C33.20541407 76.36406391 32.0321882 76.36319743 30.82341003 76.36230469 C28.95874836 76.36676137 28.95874836 76.36676137 27.05641675 76.37130809 C23.59921384 76.378497 20.14206062 76.38116808 16.68485379 76.38190556 C12.95876078 76.38375482 9.23267857 76.39130154 5.5065918 76.39804077 C-2.64426557 76.41163015 -10.79511803 76.41764981 -18.94598484 76.42205143 C-24.03449928 76.42481295 -29.12301181 76.42905015 -34.21152496 76.4335556 C-48.29729722 76.44574929 -62.38306795 76.45604826 -76.46884537 76.45943069 C-77.3706005 76.45965026 -78.27235564 76.45986983 -79.20143668 76.46009605 C-80.55732662 76.46042346 -80.55732662 76.46042346 -81.94060826 76.46075749 C-83.77234093 76.46120096 -85.6040736 76.46164753 -87.43580627 76.46209717 C-88.3444111 76.4623186 -89.25301593 76.46254003 -90.18915424 76.46276817 C-104.91177143 76.46671616 -119.63433913 76.48416978 -134.3569372 76.50745669 C-149.46872379 76.53116643 -164.58048553 76.54363238 -179.69229102 76.54479861 C-188.17848133 76.54571561 -196.66460716 76.55147411 -205.15077972 76.56961823 C-212.37713255 76.58501415 -219.60340097 76.59014271 -226.82976708 76.58188012 C-230.51670387 76.57797174 -234.20347067 76.57891225 -237.89038658 76.59293747 C-241.88817305 76.60800614 -245.885576 76.59981547 -249.88337708 76.58935547 C-251.04768006 76.59741187 -252.21198304 76.60546828 -253.41156793 76.61376882 C-259.4754651 76.57658009 -263.96858043 76.24319371 -269.28395081 73.22186279 C-273.46148936 68.82445379 -274.59921332 65.36137089 -274.49879456 59.28045654 C-274.02838933 54.77311924 -272.06349256 51.96142805 -268.84645081 48.90936279 C-265.13141433 46.46287536 -262.06023348 46.09778024 -257.66877365 46.08319759 C-256.74119303 46.07772315 -255.8136124 46.07224871 -254.85792327 46.06660837 C-253.84236428 46.06563026 -252.8268053 46.06465216 -251.7804718 46.06364441 C-250.69899628 46.05870643 -249.61752076 46.05376845 -248.50327325 46.04868084 C-244.86734182 46.0332819 -241.23142809 46.02493869 -237.59547424 46.01654053 C-234.9939305 46.00696389 -232.39238831 45.99695394 -229.79084778 45.98654175 C-224.18367499 45.9647859 -218.57649983 45.94663815 -212.96930695 45.93093681 C-204.86223516 45.90823007 -196.75518943 45.88008835 -188.64813974 45.85061754 C-175.49517941 45.8029515 -162.34220843 45.75968463 -149.18922424 45.71917725 C-136.41187206 45.6798173 -123.63452934 45.63838987 -110.85719299 45.59417725 C-109.67591909 45.59009084 -109.67591909 45.59009084 -108.47078106 45.58592188 C-104.52006867 45.57224232 -100.56935642 45.55852206 -96.61864424 45.54478157 C-63.84043262 45.43083231 -31.06220133 45.32401952 1.71604919 45.22186279 C1.04025818 44.56701904 0.36446716 43.91217529 -0.33180237 43.23748779 C-2.85979358 40.77810232 -5.37819101 38.30963235 -7.8908844 35.83465576 C-8.97546083 34.77006261 -10.06361695 33.70910258 -11.15553284 32.65203857 C-24.43303258 19.78756261 -24.43303258 19.78756261 -25.66676331 11.71795654 C-24.93011238 5.83591055 -22.96431242 1.96192602 -18.28395081 -1.77813721 C-11.90512416 -4.7870177 -5.59863032 -4.35873349 0 0 Z " fill="currentColor" transform="translate(340.28395080566406,194.77813720703125)"/><path d="M0 0 C2.99417353 2.55385389 5.3603812 5.2832624 7.125 8.8125 C7.22248974 10.49009403 7.25599492 12.17156469 7.25922108 13.85198593 C7.26367892 14.92515367 7.26813676 15.99832141 7.27272969 17.10400939 C7.27254533 18.29250306 7.27236097 19.48099674 7.27217102 20.70550537 C7.27584771 21.96071495 7.2795244 23.21592453 7.2833125 24.50917077 C7.29334855 28.00475471 7.29705187 31.50032148 7.29970181 34.9959178 C7.30360012 38.76154787 7.31317879 42.52716428 7.32191467 46.29278564 C7.34169263 55.39253816 7.35174704 64.49229181 7.36064246 73.59206045 C7.36502324 77.87493486 7.3703906 82.15780803 7.37563133 86.44068146 C7.39264505 100.67396157 7.40713983 114.90724091 7.41438007 129.14052963 C7.41629004 132.83554841 7.41821036 136.53056718 7.42016602 140.22558594 C7.42064976 141.14399042 7.4211335 142.06239491 7.4216319 143.00862983 C7.42993445 157.89043166 7.45527452 172.77215316 7.48774669 187.65391991 C7.52079729 202.9250014 7.53882703 218.19604205 7.54202431 233.46715951 C7.54419129 242.04445897 7.55296717 250.6216413 7.57848549 259.19890594 C7.60016522 266.50291425 7.60825641 273.80677699 7.59851871 281.11081383 C7.59396601 284.8381753 7.59591602 288.56523627 7.61528015 292.29255676 C7.63608701 296.33243724 7.62595234 300.37164782 7.61274719 304.41156006 C7.62371644 305.59113085 7.63468568 306.77070163 7.64598733 307.98601699 C7.59638142 314.25027207 7.33550894 318.04990331 3.125 322.8125 C2.609375 323.410625 2.09375 324.00875 1.5625 324.625 C-1.87976891 327.46861345 -6.1342979 327.14311314 -10.3671875 327.09375 C-14.08229058 326.67710293 -16.12972199 325.28994601 -18.875 322.8125 C-21.43096783 319.21290443 -22.25048524 316.76951385 -22.25531673 312.37263584 C-22.26375668 311.28652816 -22.27219662 310.20042049 -22.28089231 309.08140045 C-22.27488227 307.89386341 -22.26887222 306.70632636 -22.26268005 305.48280334 C-22.26806559 304.21686338 -22.27345113 302.95092342 -22.27899987 301.64662164 C-22.29346978 298.13494039 -22.28920503 294.62349353 -22.28183305 291.11179936 C-22.27710748 287.32371187 -22.2896058 283.53567553 -22.29985046 279.74760437 C-22.31705576 272.32735215 -22.31787491 264.90718455 -22.31263756 257.48691756 C-22.30859788 251.45637723 -22.31003701 245.42586193 -22.31538582 239.3953228 C-22.31613424 238.5373706 -22.31688266 237.6794184 -22.31765376 236.79546766 C-22.31918522 235.05259971 -22.32072357 233.30973176 -22.32226873 231.56686383 C-22.336 215.21875145 -22.33055166 198.8706869 -22.31908352 182.52257561 C-22.30915021 167.56335707 -22.32209238 152.60426221 -22.34601771 137.64506275 C-22.3703945 122.28799408 -22.3800201 106.93098303 -22.37336498 91.57389539 C-22.36986963 82.95082372 -22.37212563 74.32784718 -22.38961601 65.70479012 C-22.40432025 58.36424062 -22.4049968 51.02385325 -22.3875451 43.683307 C-22.379018 39.93770952 -22.37692689 36.19240186 -22.39219666 32.44681931 C-22.40854118 28.38737009 -22.39393055 24.32862351 -22.37620544 20.2691803 C-22.39061295 18.49122081 -22.39061295 18.49122081 -22.40531152 16.67734295 C-22.34890162 10.41969881 -22.1277795 6.54521577 -17.875 1.8125 C-12.94593274 -2.536677 -5.87012005 -2.4246148 0 0 Z " fill="currentColor" transform="translate(438.875,93.1875)"/></svg>"""

    /** Shuffle button icon for the two modes (see PlayerController shuffle codes):
     *  play-in-order glyph when off, crossed arrows for shuffle songs. */
    fun shuffleIcon(mode: Int): String = when (mode) {
        1 -> // shuffle songs
            """<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 3 21 3 21 8"></polyline><line x1="4" y1="20" x2="21" y2="3"></line><polyline points="21 16 21 21 16 21"></polyline><line x1="15" y1="15" x2="21" y2="21"></line><line x1="4" y1="4" x2="9" y2="9"></line></svg>"""
        else -> // off: play in order
            ICON_PLAY_IN_ORDER
    }

    fun shuffleTitle(mode: Int): String = when (mode) {
        1 -> "Shuffle songs"
        else -> "Play in order"
    }

    fun repeatTitle(mode: Int): String = when (mode) {
        1 -> "Repeat one song"
        3 -> "Play single song and stop"
        else -> "Repeat playlist"
    }

    /** Musicolet-style cycling buttons: each tap POSTs and the server answers with
     *  the button already in its next state, swapped in place via outerHTML. */
    fun shuffleButton(mode: Int): String =
        """<button id="np-shuffle" class="np-dot${if (mode != 0) " on" else ""}" title="${shuffleTitle(mode)}" hx-post="/api/player/shuffle" hx-swap="outerHTML">${shuffleIcon(mode)}</button>"""

    /** Repeat playlist is the base state, so only the one-song modes highlight. */
    fun repeatButton(mode: Int): String =
        """<button id="np-repeat" class="np-dot${if (mode == 1 || mode == 3) " on" else ""}" title="${repeatTitle(mode)}" hx-post="/api/player/repeat" hx-swap="outerHTML">${repeatIcon(mode)}</button>"""

    /** Repeat button icon for the three playback modes (see PlayerController repeat codes). */
    fun repeatIcon(mode: Int): String = when (mode) {
        1 -> // repeat one song
            """<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="17 1 21 5 17 9"></polyline><path d="M3 11V9a4 4 0 0 1 4-4h14"></path><polyline points="7 23 3 19 7 15"></polyline><path d="M21 13v2a4 4 0 0 1-4 4H3"></path><text x="12" y="15" font-size="9" stroke-width="1" fill="currentColor" text-anchor="middle">1</text></svg>"""
        3 -> // play single song and stop
            ICON_PLAY_SINGLE_STOP
        else -> // repeat playlist
            """<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="17 1 21 5 17 9"></polyline><path d="M3 11V9a4 4 0 0 1 4-4h14"></path><polyline points="7 23 3 19 7 15"></polyline><path d="M21 13v2a4 4 0 0 1-4 4H3"></path></svg>"""
    }

    fun nowPlayingScreen(db: MusicDatabase, lyricsOpen: Boolean): String {
        val s = PlayerController.snapshot
        if (s.trackId < 0) {
            return """
            <div class="screen" id="np-root" data-track-id="-1" style="text-align:center;">
              <button class="backlink" hx-get="/screens/library" hx-target="#main-container" style="float:left;">← Library</button>
              <div style="clear:both;"></div>
              <div class="empty">
                <div style="font-size:40px; margin-bottom:12px;">♪</div>
                Nothing playing yet.<br>Pick a song from your library.
              </div>
            </div>"""
        }
        val track = db.track(s.trackId)
        val hasLyrics = track?.lrcUri != null
        val durSec = (s.durationMs / 1000).coerceAtLeast(1)
        val posSec = s.positionMs / 1000
        val pct = if (s.durationMs > 0) s.positionMs * 100 / s.durationMs else 0
        val artBg = artColor(s.trackId)
        return """
        <div class="screen" id="np-root" data-track-id="${s.trackId}">
          <button class="backlink" hx-get="/screens/library" hx-target="#main-container">← Library</button>
          <div style="display:flex; flex-direction:column; align-items:center;">
            <div class="np-art" style="background:repeating-linear-gradient(45deg, $artBg, $artBg 14px, var(--card-bg) 14px, var(--card-bg) 28px);">
              <img src="/api/art/${s.trackId}?v=${track?.lastModified ?: 0}" alt="album art">
            </div>
            <div id="np-title" style="font-size:22px; font-weight:700; letter-spacing:-0.02em; text-align:center;">${esc(s.title)}</div>
            <div id="np-artist" style="font-size:14px; color:var(--muted); margin-top:4px; margin-bottom:20px;">${esc(s.artist)}</div>

            <div style="width:100%; max-width:340px;">
              <input id="np-slider" class="seek" type="range" min="0" max="$durSec" value="$posSec"
                     style="background:linear-gradient(to right, var(--accent) $pct%, var(--track-empty) $pct%);"
                     oninput="sliderInput(this)" onchange="sliderDone(this)">
              <div style="display:flex; justify-content:space-between; font-size:12px; color:var(--muted); font-variant-numeric:tabular-nums; margin-top:4px;">
                <span id="np-cur">${fmtTime(s.positionMs)}</span><span id="np-tot">${fmtTime(s.durationMs)}</span>
              </div>
            </div>

            <div style="display:flex; align-items:center; gap:18px; margin-top:18px;">
              ${shuffleButton(s.shuffleMode)}
              <button class="np-side" hx-post="/api/player/prev" hx-swap="none">
                <svg width="18" height="14" viewBox="0 0 18 14"><rect x="0" y="0" width="3" height="14" fill="#3b3651"></rect><polygon points="18,0 6,7 18,14" fill="#3b3651"></polygon></svg>
              </button>
              <button id="np-play" class="np-main" hx-post="/api/player/toggle" hx-swap="none"></button>
              <button class="np-side" hx-post="/api/player/next" hx-swap="none">
                <svg width="18" height="14" viewBox="0 0 18 14"><polygon points="0,0 12,7 0,14" fill="#3b3651"></polygon><rect x="15" y="0" width="3" height="14" fill="#3b3651"></rect></svg>
              </button>
              ${repeatButton(s.repeatMode)}
            </div>

            <div style="display:flex; align-items:center; gap:8px; margin-top:22px; flex-wrap:wrap; justify-content:center;">
              <button id="np-speed" class="chip" hx-post="/api/player/speed" hx-swap="none">${speedLabel(s.speed)}x</button>
              <button id="np-sleep" class="chip${if (s.sleepRemainingMs >= 0 || s.sleepSongsRemaining > 0) " on" else ""}" hx-get="/partial/sleep-menu" hx-target="#sheet-root" hx-swap="innerHTML">sleep</button>
              <button id="np-lyrics" class="chip${if (lyricsOpen) " on" else ""}" onclick="toggleLyrics(this)">lyrics</button>
              <button id="np-fav" class="chip${if (track?.favorite == true) " on" else ""}" hx-post="/api/player/favourite" hx-swap="none">favourite</button>
              <button class="chip" onclick="openQueue()">queue</button>
            </div>
            <div id="lyrics-deck-wrap" style="width:100%;">
              <div id="lyrics-deck"${if (lyricsOpen) """ hx-get="/api/player/lyrics" hx-trigger="load" hx-swap="innerHTML"""" else ""}></div>
            </div>
          </div>
        </div>
        <script>
          function toggleLyrics(btn) {
            var deck = document.getElementById('lyrics-deck');
            if (deck.innerHTML.trim() === '') {
              htmx.ajax('GET', '/api/player/lyrics', { target: deck, swap: 'innerHTML' });
              btn.classList.add('on');
              deck.parentElement.style.display = '';
            } else {
              deck.innerHTML = '';
              btn.classList.remove('on');
            }
          }
        </script>"""
    }

    fun lyricsDeckHtml(lines: List<LyricLine>): String {
        if (lines.isEmpty()) {
            return """<div class="lyrics-deck"><div class="lyric" style="text-align:center;">No lyrics found for this song.<br><span style="font-size:11px;">Drop a matching .lrc file next to the track and rescan.</span></div></div>"""
        }
        val rows = lines.joinToString("") { """<div class="lyric" data-at="${it.atMs}">${esc(it.text)}</div>""" }
        return """<div class="lyrics-deck">$rows</div>"""
    }

    /**
     * Sleep timer bottom drawer, styled like the library options drawer for
     * visual uniformity. "After (N) songs" and "Custom timer" expand inline
     * panels (stepper / minutes input) driven by the poetSleep* JS in Shell.
     */
    fun sleepDrawer(): String = """
        <div class="sheet-shield" onclick="closeSheet()"></div>
        <div class="drawer">
          <div class="sheet-grab"></div>
          <div class="drawer-head">
            <div class="drawer-head-art" style="background:var(--accent-faint); font-size:18px;">☾</div>
            <div style="flex:1; min-width:0;">
              <div style="font-size:15px; font-weight:700;">Sleep timer</div>
              <div style="font-size:12px; color:var(--muted);">Music pauses when the timer ends</div>
            </div>
          </div>
          ${drawerItem(ICON_D_CLOCK, "In 30 min",
            """hx-post="/api/player/sleep?min=30" hx-swap="none" hx-on::after-request="closeSheet()"""")}
          ${drawerItem(ICON_D_CLOCK, "In 1 hr",
            """hx-post="/api/player/sleep?min=60" hx-swap="none" hx-on::after-request="closeSheet()"""")}
          ${drawerItem(ICON_D_QUEUE, "After (N) songs", """onclick="poetSleepPanel('songs')"""",
            sub = "Stop once the songs finish", chevron = true)}
          <div id="sleep-songs-panel" class="sleep-panel" hidden>
            <button class="sleep-step" onclick="poetSleepAdj(-1)">−</button>
            <div class="sleep-count"><span id="sleep-songs-n">3</span> songs</div>
            <button class="sleep-step" onclick="poetSleepAdj(1)">+</button>
            <button class="sleep-set" onclick="poetSleepSetSongs()">Set</button>
          </div>
          ${drawerItem(ICON_D_HOURGLASS, "Custom timer", """onclick="poetSleepPanel('custom')"""",
            sub = "Pick your own minutes", chevron = true)}
          <div id="sleep-custom-panel" class="sleep-panel" hidden>
            <input id="sleep-custom-min" type="number" inputmode="numeric" min="1" max="600" value="45">
            <div class="sleep-count" style="flex:0 0 auto;">min</div>
            <button class="sleep-set" onclick="poetSleepSetCustom()">Set</button>
          </div>
          ${drawerItem(ICON_D_TIMER_OFF, "Turn off timer",
            """hx-post="/api/player/sleep?min=0" hx-swap="none" hx-on::after-request="closeSheet()"""", danger = true)}
        </div>"""

    // ---------------- queue panel ----------------

    private val ICON_QP_PLAY = """<svg width="12" height="14" viewBox="0 0 22 26" style="margin-left:2px;"><polygon points="0,0 22,13 0,26" fill="#3b3651"></polygon></svg>"""
    private val ICON_QP_PAUSE = """<div style="display:flex; gap:4px;"><div style="width:4px; height:14px; background:#3b3651; border-radius:2px;"></div><div style="width:4px; height:14px; background:#3b3651; border-radius:2px;"></div></div>"""

    /**
     * Slide-up queue panel (Musicolet-style): pinned Now Playing card with EQ
     * bars + play/pause, then the static "Next up" sequence with per-row
     * remove and drag-to-reorder handles. The header is rendered once on
     * open; #qp-body is re-rendered by every queue mutation and by the state
     * poller when the current track changes.
     */
    fun queuePanel(db: MusicDatabase, items: List<PlayerController.QueueItem>, playing: Boolean): String = """
        <div class="queue-shield" onclick="closeQueue()"></div>
        <div class="queue-panel">
          <div class="qp-hdr">
            <button class="qp-back" onclick="closeQueue()" aria-label="Close queue">
              <svg width="14" height="8" viewBox="0 0 14 8"><path d="M1 1 L7 7 L13 1" stroke="#3b3651" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"></path></svg>
            </button>
            <div style="flex:1; min-width:0;">
              <div class="qp-title">Queue</div>
              <div class="qp-src">Playing from ${esc(PlayerController.sourceName)}</div>
            </div>
            <button class="qp-clear" hx-post="/api/queue/clear" hx-target="#qp-body" hx-swap="innerHTML">Clear</button>
          </div>
          <div id="qp-body">${queuePanelBody(db, items, playing)}</div>
        </div>"""

    /** Inner content of the queue panel; swapped on every queue change. */
    fun queuePanelBody(db: MusicDatabase, items: List<PlayerController.QueueItem>, playing: Boolean): String {
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

        val nowCard = """
        <div class="qp-label">Now playing</div>
        <div class="qp-now">
          <div class="row-art" style="width:52px; height:52px; border-radius:12px;"><img src="/api/art/${cur.trackId}" alt=""></div>
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
                  <div class="row-art" hx-post="/api/queue/jump/${it.index}" hx-target="#qp-body" hx-swap="innerHTML"><img loading="lazy" src="/api/art/${it.trackId}" alt=""></div>
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

    // ---------------- settings ----------------

    fun settingsScreen(db: MusicDatabase, showTip: Boolean): String {
        val folders = db.folders()
        val folderRows = folders.joinToString("") { f ->
            """
            <div class="folder-row">
              <span style="font-size:14px;">▣</span>
              <span class="folder-path">${esc(f.displayPath)}</span>
              <button hx-get="/partial/confirm-folder/${f.id}" hx-target="#modal-root" hx-swap="innerHTML"
                      style="border:none; background:transparent; cursor:pointer; color:var(--muted); font-size:15px; padding:2px 6px; border-radius:6px;">✕</button>
            </div>"""
        }
        val tip = if (showTip) """
            <div class="tip-banner" id="tip-banner">
              <div class="tip-arrow"></div>
              <div class="tip-body">
                <div style="font-size:14px; font-weight:700; margin-bottom:4px;">Start here</div>
                <div style="font-size:13px; line-height:1.45; color:rgba(245,243,250,0.85);">Add a folder so Poet can find your music. You can add more anytime.</div>
                <button onclick="dismissTip()" style="border:none; cursor:pointer; font-family:inherit; margin-top:12px; padding:8px 14px; border-radius:99px; background:var(--accent); color:#3b3651; font-size:13px; font-weight:700;">Got it, don't show again</button>
              </div>
            </div>""" else ""
        val shield = if (showTip) """<div id="tip-shield" onclick="dismissTip()"></div>""" else ""

        val accent = db.getSetting("accent", "#b9a5ec")
        val theme = db.getSetting("theme", "Lavender")
        val dark = db.getSetting("dark", "0") == "1"
        val swatches = Shell.ACCENTS.joinToString("") { c ->
            """<button class="swatch${if (c == accent) " on" else ""}" data-c="$c" style="background:$c;" onclick="setAccent('$c')"></button>"""
        }
        val tints = Shell.CANVAS_TINTS.entries.joinToString("") { (name, bg) ->
            """<button class="tint-pill${if (name == theme) " on" else ""}" data-n="$name" style="background:$bg;" onclick="setTheme('$name','$bg')">$name</button>"""
        }

        return """
        <div class="screen" data-screen="settings" style="display:flex; flex-direction:column; gap:16px;">
          <div style="font-size:22px; font-weight:700; letter-spacing:-0.02em;">Settings</div>

          <div class="card" id="folder-card" style="position:relative; z-index:${if (showTip) 50 else 1};">
            <div class="card-title">Music folders</div>
            <div class="card-sub">Local directories scanned for audio files</div>
            <div style="display:flex; flex-direction:column; gap:8px; margin-bottom:14px;">
              ${folderRows.ifBlank { """<div style="font-size:12px; color:var(--muted); padding:4px 2px;">No folders mapped yet.</div>""" }}
            </div>
            <button class="btn-primary" style="width:100%; justify-content:center;" hx-post="/api/settings/add-folder" hx-swap="none" onclick="localStorage.setItem('poet-tip-dismissed','1')">+ Add folder directory</button>
            $tip
          </div>
          $shield

          <div class="card">
            <div class="card-title">Library scan</div>
            <div id="scan-card">${scanCard(db)}</div>
          </div>

          <div class="card">
            <div class="card-title" style="margin-bottom:4px;">Appearance</div>
            <div class="card-sub">Switch between the light and dark colour set</div>
            <div style="display:flex; gap:8px;">
              <button class="theme-opt${if (!dark) " on" else ""}" data-dark="0" onclick="setDark(false)">
                <span style="font-size:15px;">☀</span> Light
              </button>
              <button class="theme-opt${if (dark) " on" else ""}" data-dark="1" onclick="setDark(true)">
                <span style="font-size:15px;">☾</span> Dark
              </button>
            </div>
          </div>

          <div class="card">
            <div class="card-title" style="margin-bottom:12px;">Accent color</div>
            <div style="display:flex; gap:12px; margin-bottom:18px;">$swatches</div>
            <div class="card-title" style="margin-bottom:12px;">Canvas tint</div>
            <div class="card-sub">Used in light mode</div>
            <div style="display:flex; gap:8px; flex-wrap:wrap;">$tints</div>
          </div>

          <div class="card" style="cursor:pointer;" hx-get="/screens/about" hx-target="#main-container">
            <div style="display:flex; align-items:center; justify-content:space-between; gap:12px;">
              <div style="min-width:0;">
                <div class="card-title" style="margin-bottom:2px;">About Poet Music</div>
                <div class="card-sub" style="margin-bottom:0;">Version, tech stack, license &amp; developer</div>
              </div>
              <span style="color:var(--faint); font-size:20px; flex-shrink:0;">›</span>
            </div>
          </div>
        </div>"""
    }

    // ---------------- about ----------------

    /**
     * The About screen shown from Settings, built from the project's README,
     * license, security policy, tech stack and developer info and rendered
     * through the in-app [Markdown] renderer so it follows the active theme.
     */
    fun aboutScreen(db: MusicDatabase): String {
        val trackCount = db.trackCount()
        val folderCount = db.folders().size
        val md = """
# Poet Music

**Version 1.0** · offline-first, pastel-themed music player for Android.

Your library holds **$trackCount ${if (trackCount == 1) "track" else "tracks"}** across **$folderCount ${if (folderCount == 1) "folder" else "folders"}**.

Poet Music is an offline-first music player. The UI is an [htmx](https://htmx.org) single-page app served by an embedded [Ktor](https://ktor.io) server running inside the app process and rendered in a native WebView. Playback is handled natively by Media3 / ExoPlayer through a foreground `MediaSessionService`, so music keeps playing with the screen off and shows lockscreen / notification controls.

## Features

- **Local library** — pick any folder with the system file picker; Poet scans it for audio and reads tags, album art and `.lrc` lyric files.
- **Now Playing** — seek bar, playback speed, sleep timer, synced lyrics, favourites and a slide-up queue panel.
- **Musicolet-style queue** — playlists are fixed reference lists; the queue is a temporary working copy with static shuffle, per-song remove and drag-to-reorder.
- **Tag editor** — edit ID3v2 details, embed artwork and build synced `.lrc` files, written straight into MP3 files.
- **Theming** — pastel accent colours, canvas tints and a full dark mode.

## Tech stack

- **Language** — Kotlin
- **UI** — htmx single-page app in a native WebView
- **Server** — embedded Ktor (CIO) bound to `127.0.0.1:8080`
- **Playback** — Media3 / ExoPlayer in a foreground `MediaSessionService`
- **Storage** — SQLite (tracks, folders, playlists, settings)
- **Minimum Android** — 8.0 (API 26)

## Architecture

- `server/` — Ktor routes, the page shell + client JS, and server-rendered views.
- `playback/` — the foreground Media3 session and a thread-safe player bridge.
- `data/` — the SQLite database, library scanner, LRC parser and MP3 tag editor.

The embedded server binds to `127.0.0.1` only and is never reachable from other devices on the network.

## Security & privacy

- **Local-only server** — the Ktor server binds exclusively to `127.0.0.1:8080`; it is not reachable from other devices.
- **No network calls** — the app makes no outbound requests, collects no analytics and transmits no user data.
- **Scoped storage** — folders are accessed through the Storage Access Framework with user-granted permissions only.
- **Escaped output** — all user- and file-derived strings are escaped before rendering to prevent markup injection.

To report a vulnerability, open a [GitHub security advisory](https://github.com/Musa-dabwe/PoetMusic/security/advisories/new) or an issue with the `security` label.

## Developer

Built by **Musa-dabwe** (Fackson Musadabwe Mutetesha).

- **GitHub** — [Musa-dabwe](https://github.com/Musa-dabwe)
- **Repository** — [Musa-dabwe/PoetMusic](https://github.com/Musa-dabwe/PoetMusic)

## License

Licensed under the **Apache License 2.0**.

Copyright © 2026 Fackson Musadabwe Mutetesha. Licensed under the Apache License, Version 2.0; you may not use this software except in compliance with the License. The software is distributed on an "AS IS" basis, without warranties or conditions of any kind.
""".trimIndent()

        return """
        <div class="screen" data-screen="about">
          <button class="backlink" hx-get="/screens/settings" hx-target="#main-container">← Settings</button>
          <div class="card">${Markdown.render(md)}</div>
        </div>"""
    }

    /**
     * Scan card body. Only polls /partial/scan while a scan is actually
     * running: the polling wrapper is part of the swapped content, so the
     * every-2s DOM churn stops as soon as the scan finishes instead of
     * tearing the card down forever while the user reads Settings.
     */
    fun scanCard(db: MusicDatabase): String {
        val scanning = LibraryScanner.isScanning
        val status = if (scanning) esc(LibraryScanner.progressText)
        else esc(db.getSetting("last_scan", "Not scanned yet"))
        val body = """
        <div class="card-sub">$status</div>
        <button class="btn-outline" hx-post="/api/library/scan" hx-target="#scan-card" hx-swap="innerHTML" ${if (scanning) "disabled" else ""}>
          ${if (scanning) """<span class="spinner"></span> Scanning library…""" else """<span style="font-size:15px;">⟳</span> Trigger library scan"""}
        </button>"""
        return if (scanning)
            """<div hx-get="/partial/scan" hx-trigger="every 2s" hx-target="#scan-card" hx-swap="innerHTML">$body</div>"""
        else body
    }
}
