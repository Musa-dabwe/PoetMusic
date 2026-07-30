package com.musa.poetmusic.server

import com.musa.poetmusic.data.MusicDatabase

/** Library tabs, sort drawer and the album / artist / playlist detail screens. */
object LibraryViews {

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
        ${SharedViews.masterControls(ctx)}
        <div class="searchrow">
          <input id="q" name="q" type="search" placeholder="Search songs, artists, albums…" value="${esc(q)}"
                 hx-get="/partial/songs" hx-trigger="input changed delay:300ms, search" hx-target="#song-list" hx-swap="innerHTML">
          <button class="sortbtn" hx-get="/partial/sort-drawer" hx-target="#sheet-root" hx-swap="innerHTML" aria-label="Sort songs">
            <svg width="14" height="12" viewBox="0 0 14 12"><rect x="0" y="1" width="14" height="2" rx="1" fill="currentColor"></rect><rect x="0" y="5" width="10" height="2" rx="1" fill="currentColor"></rect><rect x="0" y="9" width="6" height="2" rx="1" fill="currentColor"></rect></svg>
            Sort
          </button>
        </div>
        <div id="song-list">${SharedViews.songList(tracks, ctx)}</div>"""
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
          ${SharedViews.masterControls(ctx)}
          ${SharedViews.songList(tracks, ctx)}
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
          ${SharedViews.masterControls(ctx)}
          ${SharedViews.songList(tracks, ctx)}
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
          ${if (tracks.isNotEmpty()) SharedViews.masterControls(ctx) else ""}
          ${SharedViews.songList(tracks, ctx)}
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
          ${if (tracks.isNotEmpty()) SharedViews.masterControls(ctx) else ""}
          ${SharedViews.songList(tracks, ctx)}
        </div>"""
    }
}
