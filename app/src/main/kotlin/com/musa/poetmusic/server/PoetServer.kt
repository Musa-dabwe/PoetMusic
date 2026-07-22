package com.musa.poetmusic.server

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.musa.poetmusic.data.LibraryScanner
import com.musa.poetmusic.data.LrcParser
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.data.TagEditor
import com.musa.poetmusic.playback.PlayerController
import com.musa.poetmusic.widget.WidgetRenderer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import io.ktor.server.application.ApplicationCall

/**
 * Embedded Ktor server bound to the app process on localhost:8080.
 * Serves the htmx frontend and the playback/library API.
 */
object PoetServer {

    /** Set by MainActivity so the frontend can open the SAF folder picker. */
    @Volatile var addFolderRequester: (() -> Unit)? = null

    /** Set by MainActivity so the frontend can request pinning the home screen widget. */
    @Volatile var pinWidgetRequester: (() -> Unit)? = null

    /** Set by MainActivity so the tag editor can open the gallery image picker. */
    @Volatile var pickArtRequester: (() -> Unit)? = null

    private var started = false

    /** In-memory LRU for extracted album art, bounded by bytes rather than
     *  entry count so a run of high-res covers can't balloon the heap. */
    private const val ART_CACHE_MAX_BYTES = 12 * 1024 * 1024
    private var artCacheBytes = 0L
    private val artCache = LinkedHashMap<Long, ByteArray>(16, 0.75f, true)

    /** Drop a track's cached cover so the next request re-extracts it (after a tag edit). */
    private fun artCacheEvict(id: Long) = synchronized(artCache) {
        artCache.remove(id)?.let { artCacheBytes -= it.size }
    }

    private fun artCachePut(id: Long, art: ByteArray) = synchronized(artCache) {
        // Oversized covers would evict everything else; serve them uncached.
        if (art.size > ART_CACHE_MAX_BYTES / 4) return@synchronized
        artCache.remove(id)?.let { artCacheBytes -= it.size }
        artCache[id] = art
        artCacheBytes += art.size
        val eldest = artCache.entries.iterator()
        while (artCacheBytes > ART_CACHE_MAX_BYTES && eldest.hasNext()) {
            artCacheBytes -= eldest.next().value.size
            eldest.remove()
        }
    }

    @Synchronized
    fun start(context: Context, db: MusicDatabase) {
        if (started) return
        started = true
        val app = context.applicationContext

        embeddedServer(CIO, port = 8080, host = "127.0.0.1") {
            routing {

                get("/") {
                    val accent = db.getSetting("accent", "#b9a5ec")
                    val theme = db.getSetting("theme", "Lavender")
                    val dark = db.getSetting("dark", "0") == "1"
                    call.respondText(Shell.page(accent, theme, db.folders().size, dark), ContentType.Text.Html)
                }

                get("/assets/{name}") {
                    val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.NotFound)
                    if (!name.matches(Regex("[A-Za-z0-9._-]+"))) return@get call.respond(HttpStatusCode.NotFound)
                    val type = when {
                        name.endsWith(".js") -> ContentType.parse("application/javascript")
                        name.endsWith(".woff2") -> ContentType.parse("font/woff2")
                        name.endsWith(".css") -> ContentType.Text.CSS
                        else -> ContentType.Application.OctetStream
                    }
                    val bytes = try {
                        app.assets.open("web/$name").use { it.readBytes() }
                    } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.NotFound)
                    }
                    call.response.header("Cache-Control", "max-age=86400")
                    call.respondBytes(bytes, type)
                }

                // ---------- screens ----------

                get("/screens/library") {
                    // Tab and sort fall back to the persisted state so the library
                    // looks the same after navigating away or restarting the app.
                    val tab = call.request.queryParameters["tab"]?.also { db.setSetting("lib_tab", it) }
                        ?: db.getSetting("lib_tab", "songs")
                    val q = call.request.queryParameters["q"] ?: ""
                    val sort = call.request.queryParameters["sort"]?.also { db.setSetting("lib_sort", it) }
                        ?: db.getSetting("lib_sort", "title")
                    call.respondText(Views.libraryScreen(db, tab, q, sort), ContentType.Text.Html)
                }

                get("/screens/now-playing") {
                    val lyricsOpen = call.request.queryParameters["lyrics"] == "1"
                    call.respondText(Views.nowPlayingScreen(db, lyricsOpen), ContentType.Text.Html)
                }

                get("/screens/settings") {
                    val tip = call.request.queryParameters["tip"] == "1"
                    call.respondText(Views.settingsScreen(db, tip), ContentType.Text.Html)
                }

                get("/screens/about") {
                    call.respondText(Views.aboutScreen(db), ContentType.Text.Html)
                }

                get("/screens/album") {
                    val album = call.request.queryParameters["album"] ?: ""
                    val artist = call.request.queryParameters["artist"] ?: ""
                    call.respondText(Views.albumScreen(db, album, artist), ContentType.Text.Html)
                }

                get("/screens/artist") {
                    val name = call.request.queryParameters["name"] ?: ""
                    call.respondText(Views.artistScreen(db, name), ContentType.Text.Html)
                }

                get("/screens/favorites") {
                    call.respondText(Views.favoritesScreen(db), ContentType.Text.Html)
                }

                get("/screens/playlist/{id}") {
                    val id = call.parameters["id"]?.toLongOrNull() ?: 0
                    call.respondText(Views.playlistScreen(db, id), ContentType.Text.Html)
                }

                // ---------- partials ----------

                get("/partial/songs") {
                    val q = call.request.queryParameters["q"] ?: ""
                    val sort = call.request.queryParameters["sort"]?.also { db.setSetting("lib_sort", it) }
                        ?: db.getSetting("lib_sort", "title")
                    val ctx = QueueCtx("songs", q, sort)
                    call.respondText(Views.songList(db.tracks(q, sort), ctx), ContentType.Text.Html)
                }

                get("/partial/queue") {
                    val items = PlayerController.queueItems()
                    call.respondText(Views.queuePanel(db, items, PlayerController.snapshot.playing), ContentType.Text.Html)
                }

                get("/partial/queue-body") {
                    call.respondText(queueBody(db), ContentType.Text.Html)
                }

                get("/partial/scan") {
                    call.respondText(Views.scanCard(db), ContentType.Text.Html)
                }

                get("/partial/sleep-menu") {
                    call.respondText(Views.sleepMenu(), ContentType.Text.Html)
                }

                get("/partial/sort-drawer") {
                    call.respondText(Views.sortDrawer(db.getSetting("lib_sort", "title")), ContentType.Text.Html)
                }

                /** Empty fragment: swapped into overlay roots to close them. */
                get("/partial/empty") {
                    call.respondText("", ContentType.Text.Html)
                }

                get("/partial/confirm-folder/{id}") {
                    val id = call.parameters["id"]?.toLongOrNull()
                    val folder = db.folders().firstOrNull { it.id == id }
                        ?: return@get call.respondText("", ContentType.Text.Html)
                    call.respondText(Views.confirmRemoveFolder(folder.id, folder.displayPath), ContentType.Text.Html)
                }

                get("/partial/confirm-track/{id}") {
                    val t = call.parameters["id"]?.toLongOrNull()?.let(db::track)
                        ?: return@get call.respondText("", ContentType.Text.Html)
                    call.respondText(Views.confirmRemoveTrack(t), ContentType.Text.Html)
                }

                get("/partial/confirm-playlist/{id}") {
                    val pl = call.parameters["id"]?.toLongOrNull()?.let(db::playlist)
                        ?: return@get call.respondText("", ContentType.Text.Html)
                    call.respondText(Views.confirmDeletePlaylist(pl), ContentType.Text.Html)
                }

                // ---------- player API ----------

                get("/api/player/state") {
                    val s = PlayerController.snapshot
                    val track = if (s.trackId >= 0) db.track(s.trackId) else null
                    val fav = track?.favorite == true
                    val mod = track?.lastModified ?: 0
                    val json = """{"trackId":${s.trackId},"title":${jsonStr(s.title)},"artist":${jsonStr(s.artist)},""" +
                        """"pos":${s.positionMs},"dur":${s.durationMs},"playing":${s.playing},"shuffle":${s.shuffleMode},""" +
                        """"repeat":${s.repeatMode},"speed":${s.speed},"sleep":${s.sleepRemainingMs},"hasQueue":${s.hasQueue},"fav":$fav,"mod":$mod}"""
                    call.respondText(json, ContentType.Application.Json)
                }

                post("/api/player/play/{id}") {
                    val id = call.parameters["id"]?.toLongOrNull() ?: return@post noContent()
                    val ctx = queueCtx()
                    val tracks = ctx.resolve(db)
                    val index = tracks.indexOfFirst { it.id == id }
                    if (index >= 0) PlayerController.setQueue(tracks, index, shuffled = false, source = sourceLabel(db, ctx))
                    else db.track(id)?.let { PlayerController.setQueue(listOf(it), 0, shuffled = false, source = sourceLabel(db, ctx)) }
                    noContent()
                }

                post("/api/queue/replace") {
                    val ctx = queueCtx()
                    val shuffle = call.request.queryParameters["shuffle"] == "1"
                    val tracks = ctx.resolve(db)
                    if (tracks.isEmpty()) return@post toast("Nothing to play yet")
                    PlayerController.setQueue(tracks, 0, shuffled = shuffle, source = sourceLabel(db, ctx))
                    noContent()
                }

                post("/api/queue/next/{id}") {
                    val t = call.parameters["id"]?.toLongOrNull()?.let(db::track) ?: return@post noContent()
                    PlayerController.playNext(t)
                    toast("Playing next: ${t.title}")
                }

                post("/api/queue/add/{id}") {
                    val t = call.parameters["id"]?.toLongOrNull()?.let(db::track) ?: return@post noContent()
                    PlayerController.addToQueue(t)
                    toast("Added to queue: ${t.title}")
                }

                /** Queue panel actions: each mutates the queue, then returns the
                 *  re-rendered #qp-body fragment (queueItems() runs after the
                 *  mutation on the FIFO main-thread handler, so it sees the result). */

                post("/api/queue/jump/{index}") {
                    call.parameters["index"]?.toIntOrNull()?.let(PlayerController::jumpTo)
                    call.respondText(queueBody(db), ContentType.Text.Html)
                }

                post("/api/queue/remove/{index}") {
                    call.parameters["index"]?.toIntOrNull()?.let(PlayerController::removeQueueItem)
                    call.respondText(queueBody(db), ContentType.Text.Html)
                }

                post("/api/queue/move") {
                    val p = call.receiveParameters()
                    val from = p["from"]?.toIntOrNull()
                    val to = p["to"]?.toIntOrNull()
                    if (from != null && to != null) PlayerController.moveQueueItem(from, to)
                    call.respondText(queueBody(db), ContentType.Text.Html)
                }

                post("/api/queue/clear") {
                    PlayerController.clearUpcoming()
                    call.respondText(queueBody(db), ContentType.Text.Html)
                }

                post("/api/player/favourite") {
                    val s = PlayerController.snapshot
                    val t = if (s.trackId >= 0) db.track(s.trackId) else null
                    if (t == null) return@post toast("Nothing is playing")
                    db.setFavorite(t.id, !t.favorite)
                    // The full-size widget shows the heart; keep it honest.
                    WidgetRenderer.pushUpdate(app)
                    toast(if (t.favorite) "Removed from Favourites" else "Added to Favourites ♥")
                }

                post("/api/player/toggle") { PlayerController.togglePlay(); noContent() }
                post("/api/player/next") { PlayerController.next(); noContent() }
                post("/api/player/prev") { PlayerController.previous(); noContent() }
                /* Musicolet-style state machines: one serialized controller op
                   advances the mode and returns what was actually committed; the
                   response is the button in that state (swapped in place via
                   hx-swap="outerHTML") so the tap gets instant feedback instead
                   of waiting for the poller. */
                post("/api/player/shuffle") {
                    call.respondText(Views.shuffleButton(PlayerController.advanceShuffleMode()), ContentType.Text.Html)
                }
                post("/api/player/repeat") {
                    call.respondText(Views.repeatButton(PlayerController.advanceRepeatMode()), ContentType.Text.Html)
                }
                post("/api/player/speed") { PlayerController.cycleSpeed(); noContent() }

                post("/api/player/seek") {
                    val pos = call.receiveParameters()["pos"]?.toLongOrNull()
                        ?: call.request.queryParameters["pos"]?.toLongOrNull() ?: 0
                    PlayerController.seekTo(pos)
                    noContent()
                }

                post("/api/player/sleep") {
                    val min = call.request.queryParameters["min"]?.toIntOrNull() ?: 0
                    PlayerController.setSleepTimer(min)
                    toast(if (min > 0) "Sleep timer set: $min min" else "Sleep timer off")
                }

                get("/api/player/lyrics") {
                    val s = PlayerController.snapshot
                    val track = if (s.trackId >= 0) db.track(s.trackId) else null
                    val lines = track?.lrcUri?.let { LrcParser.parse(app, it) } ?: emptyList()
                    call.respondText(Views.lyricsDeckHtml(lines), ContentType.Text.Html)
                }

                // ---------- library / menus ----------

                /** Full options drawer for one track (single ⋯) or a batch selection. */
                get("/api/library/drawer") {
                    val idsRaw = call.request.queryParameters["ids"] ?: ""
                    val tracks = idList(idsRaw).mapNotNull(db::track)
                    if (tracks.isEmpty()) return@get call.respondText("", ContentType.Text.Html)
                    call.respondText(Views.optionsDrawer(db, tracks, idsRaw, queueCtx()), ContentType.Text.Html)
                }

                /** Drawer sub-sheets: advanced queue, add-to-playlist, set-as, info, delete. */
                get("/api/library/sub") {
                    val kind = call.request.queryParameters["kind"] ?: ""
                    val idsRaw = call.request.queryParameters["ids"] ?: ""
                    val tracks = idList(idsRaw).mapNotNull(db::track)
                    if (tracks.isEmpty()) return@get call.respondText("", ContentType.Text.Html)
                    call.respondText(Views.subSheet(kind, db, tracks, idsRaw, queueCtx()), ContentType.Text.Html)
                }

                // ---------- batch track actions (single ⋯ or multi-select) ----------

                post("/api/tracks/play-now") {
                    val tracks = idList().mapNotNull(db::track)
                    if (tracks.isEmpty()) return@post toast("Nothing to play")
                    val source = if (tracks.size == 1) sourceLabel(db, queueCtx()) else "Selection"
                    PlayerController.setQueue(tracks, 0, shuffled = false, source = source)
                    toast(if (tracks.size == 1) "Playing now" else "${tracks.size} songs playing now")
                }

                post("/api/tracks/play-next") {
                    val tracks = idList().mapNotNull(db::track)
                    if (tracks.isEmpty()) return@post noContent()
                    // playNext inserts after the current track, so add in reverse to keep order.
                    tracks.asReversed().forEach(PlayerController::playNext)
                    toast(if (tracks.size == 1) "Playing next: ${tracks.first().title}" else "${tracks.size} songs play next")
                }

                post("/api/tracks/add-queue") {
                    val tracks = idList().mapNotNull(db::track)
                    if (tracks.isEmpty()) return@post noContent()
                    tracks.forEach(PlayerController::addToQueue)
                    toast(if (tracks.size == 1) "Added to queue: ${tracks.first().title}" else "Added ${tracks.size} to queue")
                }

                post("/api/tracks/advqueue") {
                    val tracks = idList().mapNotNull(db::track)
                    if (tracks.isEmpty()) return@post noContent()
                    val queue = call.request.queryParameters["queue"]?.toIntOrNull() ?: 1
                    val pos = call.request.queryParameters["pos"] ?: "Bottom"
                    // The player exposes a single active queue; alternate queues are
                    // acknowledged with a toast. Top→play next, otherwise append.
                    if (queue == 1) {
                        if (pos == "Top") tracks.asReversed().forEach(PlayerController::playNext)
                        else tracks.forEach(PlayerController::addToQueue)
                    }
                    toast("Added to Queue $queue (${pos.lowercase()})")
                }

                post("/api/tracks/add-playlists") {
                    val ids = idList()
                    val pids = idList(call.request.queryParameters["pids"] ?: "")
                    if (ids.isEmpty() || pids.isEmpty()) return@post toast("No playlist selected")
                    pids.forEach { pid -> ids.forEach { tid -> db.addToPlaylist(pid, tid) } }
                    val songWord = if (ids.size == 1) "song" else "songs"
                    val plWord = if (pids.size == 1) "playlist" else "playlists"
                    toast("Added ${ids.size} $songWord to ${pids.size} $plWord")
                }

                post("/api/tracks/delete") {
                    val ids = idList()
                    if (ids.isEmpty()) return@post noContent()
                    ids.forEach(db::removeTrack)
                    refresh(if (ids.size == 1) "Deleted 1 file" else "Deleted ${ids.size} files")
                }

                /**
                 * Sort endpoint fired by the pastel sort drawer. Maps the
                 * drawer's type slug to a library sort key, persists it, and
                 * returns the re-sorted tracklist for the #song-list target.
                 */
                get("/api/library/sort") {
                    val type = call.request.queryParameters["type"] ?: "title-az"
                    val sort = Views.SORT_STATES.firstOrNull { it.first == type }?.second ?: "title"
                    db.setSetting("lib_sort", sort)
                    val q = call.request.queryParameters["q"] ?: ""
                    val ctx = QueueCtx("songs", q, sort)
                    call.respondText(Views.songList(db.tracks(q, sort), ctx), ContentType.Text.Html)
                }

                post("/api/library/scan") {
                    if (db.folders().isEmpty()) {
                        call.response.header("HX-Trigger", """{"poet-toast":"Add a music folder first"}""")
                        call.respondText(Views.scanCard(db), ContentType.Text.Html)
                        return@post
                    }
                    LibraryScanner.startScan(app, db)
                    call.respondText(Views.scanCard(db), ContentType.Text.Html)
                }

                // ---------- tracks ----------

                post("/api/track/{id}/favorite") {
                    val t = call.parameters["id"]?.toLongOrNull()?.let(db::track) ?: return@post noContent()
                    db.setFavorite(t.id, !t.favorite)
                    refresh(if (t.favorite) "Removed from favorites" else "Added to favorites")
                }

                get("/api/library/edit-tags/{id}") {
                    val t = call.parameters["id"]?.toLongOrNull()?.let(db::track)
                        ?: return@get call.respondText("", ContentType.Text.Html)
                    // Opening the editor clears any leftover cover pick and reads
                    // the file-only fields (comment, embedded lyrics) for prefill.
                    TagEditor.clearPendingArt()
                    val extras = TagEditor.readFileExtras(app, t)
                    val isCurrent = PlayerController.snapshot.trackId == t.id
                    call.respondText(Views.tagEditorSheet(t, extras, isCurrent), ContentType.Text.Html)
                }

                put("/api/library/edit-tags/{id}") {
                    val id = call.parameters["id"]?.toLongOrNull() ?: return@put noContent()
                    val p = call.receiveParameters()
                    val form = TagEditor.Form(
                        title = p["title"] ?: "", artist = p["artist"] ?: "", album = p["album"] ?: "",
                        albumArtist = p["albumArtist"] ?: "", genre = p["genre"] ?: "", year = p["year"] ?: "",
                        trackNo = p["trackNo"] ?: "", discNo = p["discNo"] ?: "", composer = p["composer"] ?: "",
                        comment = p["comment"] ?: "", lyrics = p["lyrics"] ?: "",
                        artAction = p["artAction"] ?: "keep",
                        rename = p["rename"] == "1", renamePattern = p["renamePattern"] ?: ""
                    )
                    val result = TagEditor.saveTags(app, db, id, form)
                    if (result.artChanged) {
                        artCacheEvict(id)
                        WidgetRenderer.pushUpdate(app)
                    }
                    val artEvent = if (result.artChanged) ""","poet-art-changed":$id""" else ""
                    call.response.header(
                        "HX-Trigger",
                        """{"poet-toast":${jsonStr(result.message)},"poet-close-modal":true,"poet-refresh":true$artEvent}"""
                    )
                    call.respond(HttpStatusCode.NoContent)
                }

                /** Fire the native gallery picker; the pick lands in TagEditor.pendingArt. */
                post("/api/tageditor/pick-art") {
                    val requester = pickArtRequester
                    if (requester == null) toast("Image picker unavailable") else { requester(); noContent() }
                }

                /** Serves the cover image the user just picked, before it is saved. */
                get("/api/tageditor/art-preview") {
                    val art = TagEditor.pendingArt ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.response.header("Cache-Control", "no-store")
                    call.respondBytes(art, ContentType.parse(TagEditor.pendingArtMime))
                }

                /** Export the synced-lyrics LRC built in the editor to a sidecar file. */
                post("/api/tageditor/{id}/save-lrc") {
                    val t = call.parameters["id"]?.toLongOrNull()?.let(db::track) ?: return@post noContent()
                    val lrc = call.receiveParameters()["lrc"] ?: ""
                    val result = TagEditor.saveLrc(app, db, t, lrc)
                    toast(result.message)
                }

                post("/api/track/{id}/remove") {
                    val id = call.parameters["id"]?.toLongOrNull() ?: return@post noContent()
                    db.removeTrack(id)
                    refresh("Removed from library")
                }

                // ---------- playlists ----------

                post("/api/playlist/create") {
                    val name = call.receiveParameters()["name"]?.trim().orEmpty()
                    if (name.isEmpty()) return@post toast("Playlist needs a name")
                    val pid = db.createPlaylist(name)
                    // Optionally seed the new playlist with the drawer's target tracks.
                    call.request.queryParameters["trackId"]?.toLongOrNull()?.let { db.addToPlaylist(pid, it) }
                    idList(call.request.queryParameters["ids"] ?: "").forEach { db.addToPlaylist(pid, it) }
                    refresh("Created playlist \"$name\"")
                }

                post("/api/playlist/{pid}/add/{tid}") {
                    val pid = call.parameters["pid"]?.toLongOrNull() ?: return@post noContent()
                    val tid = call.parameters["tid"]?.toLongOrNull() ?: return@post noContent()
                    db.addToPlaylist(pid, tid)
                    toast("Added to ${db.playlist(pid)?.name ?: "playlist"}")
                }

                post("/api/playlist/{pid}/remove/{tid}") {
                    val pid = call.parameters["pid"]?.toLongOrNull() ?: return@post noContent()
                    val tid = call.parameters["tid"]?.toLongOrNull() ?: return@post noContent()
                    db.removeFromPlaylist(pid, tid)
                    refresh("Removed from playlist")
                }

                post("/api/playlist/{pid}/delete") {
                    val pid = call.parameters["pid"]?.toLongOrNull() ?: return@post noContent()
                    db.deletePlaylist(pid)
                    call.response.header(
                        "HX-Trigger",
                        """{"poet-toast":"Playlist deleted","poet-goto":"/screens/library?tab=playlists"}"""
                    )
                    call.respond(HttpStatusCode.NoContent)
                }

                // ---------- settings ----------

                post("/api/settings/add-folder") {
                    val requester = addFolderRequester
                    if (requester == null) toast("Folder picker unavailable") else { requester(); noContent() }
                }

                /** Fired by the OK button of the pastel folder-removal modal. */
                delete("/api/settings/folder") {
                    val id = call.request.queryParameters["id"]?.toLongOrNull()
                        ?: return@delete call.respond(HttpStatusCode.NoContent)
                    db.removeFolder(id)
                    call.response.header(
                        "HX-Trigger",
                        """{"poet-toast":"Folder removed","poet-refresh":true}"""
                    )
                    call.respond(HttpStatusCode.NoContent)
                }

                post("/api/settings/accent") {
                    val c = call.receiveParameters()["c"] ?: return@post noContent()
                    if (c.matches(Regex("#[0-9a-fA-F]{6}"))) {
                        db.setSetting("accent", c)
                        // Recolor the home screen widget in the same breath.
                        WidgetRenderer.pushUpdate(app)
                    }
                    noContent()
                }

                post("/api/settings/theme") {
                    val name = call.receiveParameters()["name"] ?: return@post noContent()
                    if (name in Shell.CANVAS_TINTS) {
                        db.setSetting("theme", name)
                        WidgetRenderer.pushUpdate(app)
                    }
                    noContent()
                }

                post("/api/settings/dark") {
                    val on = call.receiveParameters()["on"] == "1"
                    db.setSetting("dark", if (on) "1" else "0")
                    noContent()
                }

                post("/api/widget/pin") {
                    val requester = pinWidgetRequester
                    if (requester == null) toast("Widget pinning is unavailable right now")
                    else {
                        requester.invoke()
                        noContent()
                    }
                }

                // ---------- media ----------

                get("/api/art/{id}") {
                    val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.NotFound)
                    val cached = synchronized(artCache) { artCache[id] }
                    if (cached != null) {
                        call.response.header("Cache-Control", "max-age=3600")
                        return@get call.respondBytes(cached, ContentType.parse("image/jpeg"))
                    }
                    val track = db.track(id)
                    var art: ByteArray? = null
                    if (track != null && track.hasArt) {
                        val mmr = MediaMetadataRetriever()
                        art = try {
                            mmr.setDataSource(app, Uri.parse(track.uri))
                            mmr.embeddedPicture
                        } catch (e: Exception) {
                            null
                        } finally {
                            runCatching { mmr.release() }
                        }
                    }
                    if (art != null) {
                        artCachePut(id, art)
                        call.response.header("Cache-Control", "max-age=3600")
                        call.respondBytes(art, ContentType.parse("image/jpeg"))
                    } else {
                        val bg = artColor(id)
                        val label = esc(initials(track?.title ?: "?"))
                        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="240" height="240">""" +
                            """<rect width="240" height="240" fill="$bg"/>""" +
                            """<text x="120" y="136" font-family="sans-serif" font-size="52" font-weight="600" fill="rgba(59,54,81,0.5)" text-anchor="middle">$label</text></svg>"""
                        call.response.header("Cache-Control", "max-age=3600")
                        call.respondText(svg, ContentType.parse("image/svg+xml"))
                    }
                }

                get("/api/stream/{id}") {
                    val track = call.parameters["id"]?.toLongOrNull()?.let(db::track)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val mime = when (track.displayName.substringAfterLast('.', "").lowercase()) {
                        "mp3" -> "audio/mpeg"
                        "flac" -> "audio/flac"
                        "m4a", "aac" -> "audio/mp4"
                        "ogg", "opus" -> "audio/ogg"
                        "wav" -> "audio/wav"
                        else -> "application/octet-stream"
                    }
                    val input = try {
                        app.contentResolver.openInputStream(Uri.parse(track.uri))
                    } catch (e: Exception) {
                        null
                    } ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondOutputStream(ContentType.parse(mime)) {
                        input.use { it.copyTo(this) }
                    }
                }
            }
        }.start(wait = false)
    }

    // ---------- route helpers ----------

    /** Re-rendered inner content of the queue panel (#qp-body). */
    private fun queueBody(db: MusicDatabase): String =
        Views.queuePanelBody(db, PlayerController.queueItems(), PlayerController.snapshot.playing)

    /** Human label for the listing a queue was built from ("Playing from …"). */
    private fun sourceLabel(db: MusicDatabase, ctx: QueueCtx): String = when (ctx.ctx) {
        "album" -> ctx.album.ifBlank { "Album" }
        "artist" -> ctx.artist.ifBlank { "Artist" }
        "playlist" -> db.playlist(ctx.pid)?.name ?: "Playlist"
        "favorites" -> "Favorites"
        else -> if (ctx.q.isNotBlank()) "search “${ctx.q}”" else "All songs"
    }

    /** Parse a comma-separated list of track ids. */
    private fun idList(raw: String): List<Long> =
        raw.split(",").mapNotNull { it.trim().toLongOrNull() }

    /** Comma-separated ids from the "ids" query parameter. */
    private fun PipelineContext<Unit, ApplicationCall>.idList(): List<Long> =
        idList(call.request.queryParameters["ids"] ?: "")

    private fun PipelineContext<Unit, ApplicationCall>.queueCtx(): QueueCtx {
        val p = call.request.queryParameters
        return QueueCtx(
            ctx = p["ctx"] ?: "songs",
            q = p["q"] ?: "",
            sort = p["sort"] ?: "title",
            album = p["album"] ?: "",
            artist = p["artist"] ?: "",
            pid = p["pid"]?.toLongOrNull() ?: 0
        )
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.noContent() {
        call.respond(HttpStatusCode.NoContent)
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.toast(msg: String) {
        call.response.header("HX-Trigger", """{"poet-toast":${jsonStr(msg)}}""")
        call.respond(HttpStatusCode.NoContent)
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.refresh(msg: String) {
        call.response.header("HX-Trigger", """{"poet-toast":${jsonStr(msg)},"poet-refresh":true}""")
        call.respond(HttpStatusCode.NoContent)
    }
}
