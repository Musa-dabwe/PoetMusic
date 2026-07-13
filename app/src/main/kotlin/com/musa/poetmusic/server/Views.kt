package com.musa.poetmusic.server

import com.musa.poetmusic.data.LibraryScanner
import com.musa.poetmusic.data.LyricLine
import com.musa.poetmusic.data.MusicDatabase
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

    fun songRow(t: Track, ctx: QueueCtx): String {
        val cq = ctx.query()
        return """
        <div class="row" data-track-id="${t.id}" data-menu-url="/api/library/menu/${t.id}?$cq">
          <div class="row-art" style="background:${artColor(t.id)};" hx-post="/api/player/play/${t.id}?$cq" hx-swap="none">${
            if (t.hasArt) """<img loading="lazy" src="/api/art/${t.id}" alt="">""" else esc(initials(t.title))
        }</div>
          <div class="row-main" hx-post="/api/player/play/${t.id}?$cq" hx-swap="none">
            <div class="row-title">${esc(t.title)}${if (t.favorite) """ <span style="color:var(--accent);">♥</span>""" else ""}</div>
            <div class="row-sub">${esc(t.artist)}</div>
          </div>
          <div class="row-dur" hx-post="/api/player/play/${t.id}?$cq" hx-swap="none">${fmtTime(t.durationMs)}</div>
          <button class="row-menu-btn" hx-get="/api/library/menu/${t.id}?$cq" hx-target="next .menu-slot" hx-swap="innerHTML" onclick="closeMenus()">⋯</button>
          <div class="menu-slot"></div>
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
        val sortOptions = listOf(
            "title" to "Title A-Z",
            "title_desc" to "Title Z-A",
            "artist_desc" to "Artist Descending",
            "artist" to "Artist Ascending",
            "date_added" to "Date Added"
        ).joinToString("") { (v, l) -> """<option value="$v"${if (v == sort) " selected" else ""}>$l</option>""" }
        return """
        ${masterControls(ctx)}
        <div class="searchrow">
          <input id="q" name="q" type="search" placeholder="Search songs, artists, albums…" value="${esc(q)}"
                 hx-get="/partial/songs" hx-include="#sort" hx-trigger="input changed delay:300ms, search" hx-target="#song-list" hx-swap="innerHTML">
          <select id="sort" name="sort" hx-get="/partial/songs" hx-include="#q" hx-trigger="change" hx-target="#song-list" hx-swap="innerHTML">$sortOptions</select>
        </div>
        <div id="song-list">${songList(tracks, ctx)}</div>"""
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
                 style="flex:1; min-width:0; border:none; border-radius:12px; padding:10px 14px; font-family:inherit; font-size:14px; color:var(--ink); background:rgba(59,54,81,0.06);">
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
            <button class="pill" hx-post="/api/playlist/${pl.id}/delete" hx-confirm="Delete playlist &quot;${esc(pl.name)}&quot;?" hx-swap="none">Delete</button>
          </div>
          <div style="font-size:13px; color:var(--muted); margin-bottom:16px;">${tracks.size} songs</div>
          ${if (tracks.isNotEmpty()) masterControls(ctx) else ""}
          ${songList(tracks, ctx)}
        </div>"""
    }

    // ---------------- context menu ----------------

    fun contextMenu(db: MusicDatabase, t: Track, ctx: QueueCtx): String {
        val removeFromPlaylist = if (ctx.ctx == "playlist") {
            """<button hx-post="/api/playlist/${ctx.pid}/remove/${t.id}" hx-swap="none" onclick="closeMenus()">Remove from this playlist</button>"""
        } else ""
        return """
        <div class="menu-shield" onclick="event.stopPropagation(); closeMenus()"></div>
        <div class="menu" onclick="event.stopPropagation()">
          <button hx-post="/api/queue/next/${t.id}" hx-swap="none" onclick="closeMenus()">Play next</button>
          <button hx-post="/api/queue/add/${t.id}" hx-swap="none" onclick="closeMenus()">Add to queue</button>
          <button hx-get="/api/library/menu/${t.id}/playlists?${ctx.query()}" hx-target="closest .menu-slot" hx-swap="innerHTML">Add to playlist ›</button>
          <button hx-post="/api/track/${t.id}/favorite" hx-swap="none" onclick="closeMenus()">${if (t.favorite) "Remove from favorites" else "Add to favorites ♥"}</button>
          <button hx-get="/api/track/${t.id}/tags" hx-target="#modal-root" hx-swap="innerHTML" onclick="closeMenus()">Edit tags</button>
          $removeFromPlaylist
          <button hx-post="/api/track/${t.id}/remove" hx-confirm="Remove &quot;${esc(t.title)}&quot; from the library? The file is kept on disk." hx-swap="none" onclick="closeMenus()" style="color:#b85c5c;">Remove from library</button>
        </div>"""
    }

    fun playlistSubmenu(db: MusicDatabase, t: Track, ctx: QueueCtx): String {
        val items = db.playlists().joinToString("") { p ->
            """<button hx-post="/api/playlist/${p.id}/add/${t.id}" hx-swap="none" onclick="closeMenus()">${esc(p.name)}</button>"""
        }
        return """
        <div class="menu-shield" onclick="event.stopPropagation(); closeMenus()"></div>
        <div class="menu" onclick="event.stopPropagation()">
          <button hx-get="/api/library/menu/${t.id}?${ctx.query()}" hx-target="closest .menu-slot" hx-swap="innerHTML">‹ Back</button>
          $items
          <form class="menu-form" hx-post="/api/playlist/create?trackId=${t.id}" hx-swap="none" hx-on::after-request="closeMenus()">
            <input name="name" placeholder="New playlist" required>
            <button type="submit">＋</button>
          </form>
        </div>"""
    }

    // ---------------- tag editor modal ----------------

    fun tagEditorModal(t: Track): String = """
        <div class="modal-shield" onclick="if(event.target===this) document.getElementById('modal-root').innerHTML=''">
          <div class="modal">
            <div style="font-size:17px; font-weight:700; letter-spacing:-0.02em;">Edit tags</div>
            <div style="font-size:12px; color:var(--muted); margin-top:2px; font-family:monospace; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">${esc(t.displayName)}</div>
            <form hx-post="/api/track/${t.id}/tags" hx-swap="none">
              <label for="tag-title">Title</label>
              <input id="tag-title" name="title" value="${esc(t.title)}" required>
              <label for="tag-artist">Artist</label>
              <input id="tag-artist" name="artist" value="${esc(t.artist)}">
              <label for="tag-album">Album</label>
              <input id="tag-album" name="album" value="${esc(t.album)}">
              <div style="font-size:11px; color:var(--muted); margin-top:10px;">${
                if (t.displayName.endsWith(".mp3", true)) "ID3v2 tags will be written into the MP3 file."
                else "Non-MP3 file: tags are saved to the Poet library only."
              }</div>
              <div style="display:flex; gap:10px; margin-top:16px; justify-content:flex-end;">
                <button type="button" class="pill" onclick="document.getElementById('modal-root').innerHTML=''">Cancel</button>
                <button type="submit" class="btn-primary">Save tags</button>
              </div>
            </form>
          </div>
        </div>"""

    // ---------------- now playing ----------------

    /** Shuffle button icon: crossed arrows when shuffling, ordered list when playing in order. */
    fun shuffleIcon(on: Boolean): String = if (on)
        """<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 3 21 3 21 8"></polyline><line x1="4" y1="20" x2="21" y2="3"></line><polyline points="21 16 21 21 16 21"></polyline><line x1="15" y1="15" x2="21" y2="21"></line><line x1="4" y1="4" x2="9" y2="9"></line></svg>"""
    else
        """<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="6" x2="20" y2="6"></line><line x1="4" y1="12" x2="20" y2="12"></line><line x1="4" y1="18" x2="12" y2="18"></line><polyline points="16 15 19 18 16 21"></polyline></svg>"""

    /** Repeat button icon for the four playback modes (see PlayerController repeat codes). */
    fun repeatIcon(mode: Int): String = when (mode) {
        1 -> // repeat one song
            """<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="17 1 21 5 17 9"></polyline><path d="M3 11V9a4 4 0 0 1 4-4h14"></path><polyline points="7 23 3 19 7 15"></polyline><path d="M21 13v2a4 4 0 0 1-4 4H3"></path><text x="12" y="15" font-size="9" stroke-width="1" fill="currentColor" text-anchor="middle">1</text></svg>"""
        2 -> // repeat playlist
            """<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="17 1 21 5 17 9"></polyline><path d="M3 11V9a4 4 0 0 1 4-4h14"></path><polyline points="7 23 3 19 7 15"></polyline><path d="M21 13v2a4 4 0 0 1-4 4H3"></path></svg>"""
        3 -> // play single song and stop
            """<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><text x="8" y="17" font-size="13" stroke-width="1" fill="currentColor" text-anchor="middle">1</text><rect x="14" y="9" width="6" height="6" fill="currentColor" stroke="none"></rect></svg>"""
        else -> // off: play queue through, then stop
            """<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="12" x2="19" y2="12"></line><polyline points="13 6 19 12 13 18"></polyline></svg>"""
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
            <div class="np-art" style="background:repeating-linear-gradient(45deg, $artBg, $artBg 14px, #ffffff 14px, #ffffff 28px);">
              ${if (track?.hasArt == true) """<img src="/api/art/${s.trackId}" alt="album art">""" else ""}
            </div>
            <div id="np-title" style="font-size:22px; font-weight:700; letter-spacing:-0.02em; text-align:center;">${esc(s.title)}</div>
            <div id="np-artist" style="font-size:14px; color:var(--muted); margin-top:4px; margin-bottom:20px;">${esc(s.artist)}</div>

            <div style="width:100%; max-width:340px;">
              <input id="np-slider" class="seek" type="range" min="0" max="$durSec" value="$posSec"
                     style="background:linear-gradient(to right, var(--accent) $pct%, rgba(59,54,81,0.12) $pct%);"
                     oninput="sliderInput(this)" onchange="sliderDone(this)">
              <div style="display:flex; justify-content:space-between; font-size:12px; color:var(--muted); font-variant-numeric:tabular-nums; margin-top:4px;">
                <span id="np-cur">${fmtTime(s.positionMs)}</span><span id="np-tot">${fmtTime(s.durationMs)}</span>
              </div>
            </div>

            <div style="display:flex; align-items:center; gap:18px; margin-top:18px;">
              <button id="np-shuffle" class="np-dot${if (s.shuffle) " on" else ""}" title="${if (s.shuffle) "Shuffle all" else "Play in order"}" hx-post="/api/player/shuffle" hx-swap="none">${shuffleIcon(s.shuffle)}</button>
              <button class="np-side" hx-post="/api/player/prev" hx-swap="none">
                <svg width="18" height="14" viewBox="0 0 18 14"><rect x="0" y="0" width="3" height="14" fill="#3b3651"></rect><polygon points="18,0 6,7 18,14" fill="#3b3651"></polygon></svg>
              </button>
              <button id="np-play" class="np-main" hx-post="/api/player/toggle" hx-swap="none"></button>
              <button class="np-side" hx-post="/api/player/next" hx-swap="none">
                <svg width="18" height="14" viewBox="0 0 18 14"><polygon points="0,0 12,7 0,14" fill="#3b3651"></polygon><rect x="15" y="0" width="3" height="14" fill="#3b3651"></rect></svg>
              </button>
              <button id="np-repeat" class="np-dot${if (s.repeatMode != 0) " on" else ""}" hx-post="/api/player/repeat" hx-swap="none">${repeatIcon(s.repeatMode)}</button>
            </div>

            <div style="display:flex; align-items:center; gap:8px; margin-top:22px; flex-wrap:wrap; justify-content:center;">
              <button id="np-speed" class="chip" hx-post="/api/player/speed" hx-swap="none">${speedLabel(s.speed)}x</button>
              <button id="np-sleep" class="chip${if (s.sleepRemainingMs >= 0) " on" else ""}" hx-get="/partial/sleep-menu" hx-target="#sleep-slot" hx-swap="innerHTML">sleep</button>
              <button id="np-lyrics" class="chip${if (lyricsOpen) " on" else ""}" onclick="toggleLyrics(this)">lyrics</button>
              <button id="np-fav" class="chip${if (track?.favorite == true) " on" else ""}" hx-post="/api/player/favourite" hx-swap="none">favourite</button>
              <button class="chip" onclick="openQueue()">queue</button>
            </div>
            <div id="sleep-slot" class="menu-slot" style="position:relative; z-index:45; width:100%;"></div>

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

    fun sleepMenu(): String = """
        <div class="menu-shield" onclick="event.stopPropagation(); closeMenus()"></div>
        <div class="menu" onclick="event.stopPropagation()" style="left:50%; right:auto; top:6px; transform:translateX(-50%);">
          <button hx-post="/api/player/sleep?min=15" hx-swap="none" onclick="closeMenus()">In 15 minutes</button>
          <button hx-post="/api/player/sleep?min=30" hx-swap="none" onclick="closeMenus()">In 30 minutes</button>
          <button hx-post="/api/player/sleep?min=45" hx-swap="none" onclick="closeMenus()">In 45 minutes</button>
          <button hx-post="/api/player/sleep?min=60" hx-swap="none" onclick="closeMenus()">In 1 hour</button>
          <button hx-post="/api/player/sleep?min=0" hx-swap="none" onclick="closeMenus()">Turn off timer</button>
        </div>"""

    // ---------------- queue drawer ----------------

    /** Slide-up drawer listing the live play queue in its active shuffle order. */
    fun queueDrawer(items: List<PlayerController.QueueItem>): String {
        val body = if (items.isEmpty()) {
            """<div class="empty">The queue is empty.<br>Pick a song from your library.</div>"""
        } else {
            items.mapIndexed { pos, it ->
                """
                <div class="row${if (it.current) " playing" else ""}" hx-post="/api/player/jump/${it.index}" hx-swap="none" onclick="closeQueue()">
                  <div class="queue-num">${pos + 1}</div>
                  <div class="row-main">
                    <div class="row-title">${esc(it.title)}</div>
                    <div class="row-sub">${esc(it.artist)}</div>
                  </div>
                  ${if (it.current) """<div style="font-size:11px; font-weight:700; color:var(--accent);">now</div>""" else ""}
                </div>"""
            }.joinToString("")
        }
        return """
        <div class="queue-shield" onclick="closeQueue()"></div>
        <div class="queue-panel">
          <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:10px;">
            <div style="font-size:17px; font-weight:700; letter-spacing:-0.02em;">Up next <span style="font-size:12px; font-weight:600; color:var(--muted);">· ${items.size} songs</span></div>
            <button class="pill" onclick="closeQueue()">Close</button>
          </div>
          <div class="queue-list">$body</div>
        </div>"""
    }

    // ---------------- settings ----------------

    fun settingsScreen(db: MusicDatabase, showTip: Boolean): String {
        val folders = db.folders()
        val folderRows = folders.joinToString("") { f ->
            """
            <div class="folder-row">
              <span style="font-size:14px;">▣</span>
              <span class="folder-path">${esc(f.displayPath)}</span>
              <button hx-post="/api/settings/remove-folder/${f.id}" hx-confirm="Remove this folder and its songs from the library?" hx-swap="none"
                      style="border:none; background:transparent; cursor:pointer; color:var(--muted); font-size:15px; padding:2px 6px; border-radius:6px;">✕</button>
            </div>"""
        }
        val tip = if (showTip) """
            <div class="tip-banner" id="tip-banner">
              <div class="tip-arrow"></div>
              <div class="tip-body">
                <div style="font-size:14px; font-weight:700; margin-bottom:4px;">Start here</div>
                <div style="font-size:13px; line-height:1.45; color:rgba(245,243,250,0.85);">Add a folder so Poet can find your music. You can add more anytime.</div>
                <button onclick="dismissTip()" style="border:none; cursor:pointer; font-family:inherit; margin-top:12px; padding:8px 14px; border-radius:99px; background:var(--accent); color:var(--ink); font-size:13px; font-weight:700;">Got it, don't show again</button>
              </div>
            </div>""" else ""
        val shield = if (showTip) """<div id="tip-shield" onclick="dismissTip()"></div>""" else ""

        val accent = db.getSetting("accent", "#b9a5ec")
        val theme = db.getSetting("theme", "Lavender")
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
            <div id="scan-card" hx-get="/partial/scan" hx-trigger="every 2s" hx-swap="innerHTML">${scanCard(db)}</div>
          </div>

          <div class="card">
            <div class="card-title" style="margin-bottom:12px;">Accent color</div>
            <div style="display:flex; gap:12px; margin-bottom:18px;">$swatches</div>
            <div class="card-title" style="margin-bottom:12px;">Canvas tint</div>
            <div style="display:flex; gap:8px; flex-wrap:wrap;">$tints</div>
          </div>

          <div class="card">
            <div class="card-title">About</div>
            <div style="font-size:12px; color:var(--muted); line-height:1.6;">
              Poet Music 1.0 — offline-first pastel player.<br>
              ${db.trackCount()} tracks · ${folders.size} folders mapped.<br>
              Long-press any song for quick actions; MP3 tags are editable in place.
            </div>
          </div>
        </div>"""
    }

    fun scanCard(db: MusicDatabase): String {
        val scanning = LibraryScanner.isScanning
        val status = if (scanning) esc(LibraryScanner.progressText)
        else esc(db.getSetting("last_scan", "Not scanned yet"))
        return """
        <div class="card-sub">$status</div>
        <button class="btn-outline" hx-post="/api/library/scan" hx-target="#scan-card" hx-swap="innerHTML" ${if (scanning) "disabled" else ""}>
          ${if (scanning) """<span class="spinner"></span> Scanning library…""" else """<span style="font-size:15px;">⟳</span> Trigger library scan"""}
        </button>"""
    }
}
