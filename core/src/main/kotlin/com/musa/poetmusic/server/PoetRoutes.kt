package com.musa.poetmusic.server

import com.musa.poetmusic.data.BatchTagForm
import com.musa.poetmusic.data.HostPort
import com.musa.poetmusic.data.LibraryStore
import com.musa.poetmusic.data.LrcParser
import com.musa.poetmusic.data.ScanPort
import com.musa.poetmusic.data.TagForm
import com.musa.poetmusic.data.TagPort
import com.musa.poetmusic.data.Track
import com.musa.poetmusic.data.audioMime
import com.musa.poetmusic.playback.EqPort
import com.musa.poetmusic.playback.PlayerPort
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
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

/**
 * Everything a Poet frontend needs from its platform, in one bundle
 * (docs/desktop-app-plan.md §2.3).
 *
 * Both builds construct one of these and hand it to [poetRoutes]; nothing
 * below this line knows whether it is running on a phone or a desktop.
 */
class PoetDeps(
    val store: LibraryStore,
    val player: PlayerPort,
    val eq: EqPort,
    val scanner: ScanPort,
    val tags: TagPort,
    val host: HostPort,
    val about: AboutSpec
)

/**
 * The whole HTTP surface of Poet: the page shell, the server-rendered screens
 * and partials, and the playback / library API the htmx frontend drives.
 *
 * This used to be `PoetServer.kt` in `:app`, written against `MusicDatabase`
 * and `PlayerController` directly. It lives in `:core` now so the Android app
 * and the Linux desktop app serve byte-identical HTML from one implementation
 * — the module has no Android on its compile classpath, so that cannot quietly
 * stop being true.
 *
 * The `127.0.0.1`-only bind is a load-bearing security property on both
 * platforms (README, SECURITY.md, the About screen) and belongs to the caller,
 * which owns the engine.
 */
fun Application.poetRoutes(deps: PoetDeps) {
    val store = deps.store
    val player = deps.player
    val host = deps.host
    val art = ArtCache(host)

    routing {

        get("/") {
            val accent = store.getSetting("accent", "#b9a5ec")
            val theme = store.getSetting("theme", "Lavender")
            val dark = store.getSetting("dark", "0") == "1"
            call.respondText(Shell.page(accent, theme, store.folders().size, dark), ContentType.Text.Html)
        }

        get("/assets/{name}") {
            val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.NotFound)
            if (!name.matches(Regex("[A-Za-z0-9._-]+"))) return@get call.respond(HttpStatusCode.NotFound)
            val type = when {
                name.endsWith(".js") -> ContentType.parse("application/javascript")
                name.endsWith(".woff2") -> ContentType.parse("font/woff2")
                name.endsWith(".css") -> ContentType.Text.CSS
                name.endsWith(".svg") -> ContentType.Image.SVG
                else -> ContentType.Application.OctetStream
            }
            val bytes = host.asset(name) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.header("Cache-Control", "max-age=86400")
            call.respondBytes(bytes, type)
        }

        // ---------- screens ----------

        get("/screens/library") {
            // Tab and sort fall back to the persisted state so the library
            // looks the same after navigating away or restarting the app.
            // Each tab keeps its own sort (LibrarySort), so switching tabs
            // never rewrites another tab's order.
            val tab = call.request.queryParameters["tab"]?.also { store.setSetting("lib_tab", it) }
                ?: store.getSetting("lib_tab", "songs")
            val q = call.request.queryParameters["q"] ?: ""
            val sort = call.request.queryParameters["sort"]?.also { LibrarySort.write(store, tab, it) }
                ?: LibrarySort.read(store, tab)
            call.respondText(LibraryViews.libraryScreen(store, tab, q, sort), ContentType.Text.Html)
        }

        get("/screens/now-playing") {
            val lyricsOpen = call.request.queryParameters["lyrics"] == "1"
            call.respondText(
                NowPlayingViews.nowPlayingScreen(store, player.snapshot, lyricsOpen),
                ContentType.Text.Html
            )
        }

        get("/screens/settings") {
            val tip = call.request.queryParameters["tip"] == "1"
            call.respondText(settingsScreen(deps, tip), ContentType.Text.Html)
        }

        get("/screens/about") {
            call.respondText(SettingsViews.aboutScreen(store, deps.about), ContentType.Text.Html)
        }

        get("/screens/album") {
            val album = call.request.queryParameters["album"] ?: ""
            val artist = call.request.queryParameters["artist"] ?: ""
            call.respondText(LibraryViews.albumScreen(store, album, artist), ContentType.Text.Html)
        }

        get("/screens/artist") {
            val name = call.request.queryParameters["name"] ?: ""
            call.respondText(LibraryViews.artistScreen(store, name), ContentType.Text.Html)
        }

        get("/screens/genre") {
            val name = call.request.queryParameters["name"] ?: ""
            call.respondText(LibraryViews.genreScreen(store, name), ContentType.Text.Html)
        }

        get("/screens/journal") {
            call.respondText(
                JournalViews.journalScreen(store.journalStats(), host.portraitExists(), host.portraitStamp()),
                ContentType.Text.Html
            )
        }

        get("/screens/favorites") {
            call.respondText(LibraryViews.favoritesScreen(store), ContentType.Text.Html)
        }

        get("/screens/playlist/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: 0
            call.respondText(LibraryViews.playlistScreen(store, id), ContentType.Text.Html)
        }

        // ---------- partials ----------

        get("/partial/songs") {
            val q = call.request.queryParameters["q"] ?: ""
            val sort = call.request.queryParameters["sort"]?.also { LibrarySort.write(store, "songs", it) }
                ?: LibrarySort.read(store, "songs")
            val ctx = QueueCtx("songs", q, sort)
            call.respondText(songPage(store, ctx, 0), ContentType.Text.Html)
        }

        /** Next page of a tracklist, swapped over the "Load more" sentinel. */
        get("/partial/songs-page") {
            val ctx = queueCtx()
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val total = ctx.total(store)
            val page = ctx.resolvePage(store, offset, SharedViews.PAGE_SIZE)
            call.respondText(SharedViews.songListPage(page, ctx, offset, total), ContentType.Text.Html)
        }

        get("/partial/queue") {
            call.respondText(
                QueueViews.queuePanel(store, player.queueItems(), player.snapshot.playing, player.sourceName),
                ContentType.Text.Html
            )
        }

        get("/partial/queue-body") {
            call.respondText(queueBody(deps), ContentType.Text.Html)
        }

        get("/partial/scan") {
            call.respondText(SettingsViews.scanCard(store, deps.scanner), ContentType.Text.Html)
        }

        /** Full-screen cover viewer. */
        get("/partial/art-view/{id}") {
            val t = call.parameters["id"]?.toLongOrNull()?.let(store::track)
                ?: return@get call.respondText("", ContentType.Text.Html)
            call.respondText(
                NowPlayingViews.artViewer(t.id, t.title, t.artist, t.lastModified),
                ContentType.Text.Html
            )
        }

        get("/partial/sleep-menu") {
            call.respondText(NowPlayingViews.sleepDrawer(), ContentType.Text.Html)
        }

        get("/partial/sort-drawer") {
            val tab = sortTab()
            call.respondText(LibraryViews.sortDrawer(tab, LibrarySort.read(store, tab)), ContentType.Text.Html)
        }

        /** Empty fragment: swapped into overlay roots to close them. */
        get("/partial/empty") {
            call.respondText("", ContentType.Text.Html)
        }

        get("/partial/confirm-folder/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            val folder = store.folders().firstOrNull { it.id == id }
                ?: return@get call.respondText("", ContentType.Text.Html)
            call.respondText(DrawerViews.confirmRemoveFolder(folder.id, folder.displayPath), ContentType.Text.Html)
        }

        get("/partial/confirm-track/{id}") {
            val t = call.parameters["id"]?.toLongOrNull()?.let(store::track)
                ?: return@get call.respondText("", ContentType.Text.Html)
            call.respondText(DrawerViews.confirmRemoveTrack(t), ContentType.Text.Html)
        }

        get("/partial/confirm-playlist/{id}") {
            val pl = call.parameters["id"]?.toLongOrNull()?.let(store::playlist)
                ?: return@get call.respondText("", ContentType.Text.Html)
            call.respondText(DrawerViews.confirmDeletePlaylist(pl), ContentType.Text.Html)
        }

        // ---------- player API ----------

        get("/api/player/state") {
            val s = player.snapshot
            val track = if (s.trackId >= 0) store.track(s.trackId) else null
            val fav = track?.favorite == true
            val mod = track?.lastModified ?: 0
            val json = """{"trackId":${s.trackId},"title":${jsonStr(s.title)},"artist":${jsonStr(s.artist)},""" +
                """"pos":${s.positionMs},"dur":${s.durationMs},"playing":${s.playing},"shuffle":${s.shuffleMode},""" +
                """"repeat":${s.repeatMode},"speed":${s.speed},"sleep":${s.sleepRemainingMs},"sleepSongs":${s.sleepSongsRemaining},"hasQueue":${s.hasQueue},"fav":$fav,"mod":$mod}"""
            call.respondText(json, ContentType.Application.Json)
        }

        post("/api/player/play/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post noContent()
            val ctx = queueCtx()
            val tracks = ctx.resolve(store)
            val index = tracks.indexOfFirst { it.id == id }
            if (index >= 0) player.setQueue(tracks, index, shuffled = false, source = sourceLabel(store, ctx))
            else store.track(id)?.let { player.setQueue(listOf(it), 0, shuffled = false, source = sourceLabel(store, ctx)) }
            noContent()
        }

        post("/api/queue/replace") {
            val ctx = queueCtx()
            val shuffle = call.request.queryParameters["shuffle"] == "1"
            val tracks = ctx.resolve(store)
            if (tracks.isEmpty()) return@post toast("Nothing to play yet")
            player.setQueue(tracks, 0, shuffled = shuffle, source = sourceLabel(store, ctx))
            noContent()
        }

        post("/api/queue/next/{id}") {
            val t = call.parameters["id"]?.toLongOrNull()?.let(store::track) ?: return@post noContent()
            player.playNext(t)
            toast("Playing next: ${t.title}")
        }

        post("/api/queue/add/{id}") {
            val t = call.parameters["id"]?.toLongOrNull()?.let(store::track) ?: return@post noContent()
            player.addToQueue(t)
            toast("Added to queue: ${t.title}")
        }

        /* Queue panel actions: each mutates the queue, then returns the
           re-rendered #qp-body fragment (queueItems() observes the mutation
           because the implementations serialize both onto one handler). */

        post("/api/queue/jump/{index}") {
            call.parameters["index"]?.toIntOrNull()?.let(player::jumpTo)
            call.respondText(queueBody(deps), ContentType.Text.Html)
        }

        post("/api/queue/remove/{index}") {
            call.parameters["index"]?.toIntOrNull()?.let(player::removeQueueItem)
            call.respondText(queueBody(deps), ContentType.Text.Html)
        }

        post("/api/queue/move") {
            val p = call.receiveParameters()
            val from = p["from"]?.toIntOrNull()
            val to = p["to"]?.toIntOrNull()
            if (from != null && to != null) player.moveQueueItem(from, to)
            call.respondText(queueBody(deps), ContentType.Text.Html)
        }

        post("/api/queue/clear") {
            player.clearUpcoming()
            call.respondText(queueBody(deps), ContentType.Text.Html)
        }

        post("/api/player/favourite") {
            val s = player.snapshot
            val t = if (s.trackId >= 0) store.track(s.trackId) else null
            if (t == null) return@post toast("Nothing is playing")
            store.setFavorite(t.id, !t.favorite)
            host.onLibraryChanged()
            toast(if (t.favorite) "Removed from Favourites" else "Added to Favourites ♥")
        }

        post("/api/player/toggle") { player.togglePlay(); noContent() }
        post("/api/player/next") { player.next(); noContent() }
        post("/api/player/prev") { player.previous(); noContent() }

        /* Musicolet-style state machines: one serialized controller op advances
           the mode and returns what was actually committed; the response is the
           button in that state (swapped in place via hx-swap="outerHTML") so the
           tap gets instant feedback instead of waiting for the poller. */
        post("/api/player/shuffle") {
            val mode = player.advanceShuffleMode()
            call.response.header("HX-Trigger", """{"poet-toast-accent":${jsonStr(NowPlayingViews.shuffleTitle(mode))}}""")
            call.respondText(NowPlayingViews.shuffleButton(mode), ContentType.Text.Html)
        }
        post("/api/player/repeat") {
            val mode = player.advanceRepeatMode()
            call.response.header("HX-Trigger", """{"poet-toast-accent":${jsonStr(NowPlayingViews.repeatTitle(mode))}}""")
            call.respondText(NowPlayingViews.repeatButton(mode), ContentType.Text.Html)
        }
        post("/api/player/speed") { player.cycleSpeed(); noContent() }

        post("/api/player/seek") {
            val pos = call.receiveParameters()["pos"]?.toLongOrNull()
                ?: call.request.queryParameters["pos"]?.toLongOrNull() ?: 0
            player.seekTo(pos)
            noContent()
        }

        post("/api/player/sleep") {
            val min = call.request.queryParameters["min"]?.toIntOrNull() ?: 0
            player.setSleepTimer(min)
            toastAccent(if (min > 0) "Sleep timer set: $min min" else "Sleep timer off")
        }

        post("/api/player/sleep-songs") {
            val n = call.request.queryParameters["n"]?.toIntOrNull()?.coerceIn(0, 99) ?: 0
            player.setSleepSongs(n)
            toastAccent(if (n > 0) "Sleeping after $n ${if (n == 1) "song" else "songs"}" else "Sleep timer off")
        }

        get("/api/player/lyrics") {
            val s = player.snapshot
            val track = if (s.trackId >= 0) store.track(s.trackId) else null
            val lines = track?.lrcUri?.let { host.readText(it) }?.let(LrcParser::parse) ?: emptyList()
            call.respondText(NowPlayingViews.lyricsDeckHtml(lines), ContentType.Text.Html)
        }

        // ---------- library / menus ----------

        /** Full options drawer for one track (single ⋯) or a batch selection.
         *  The ids string handed to the view is rebuilt from the parsed numeric
         *  list, never the raw query value: it is interpolated into attributes
         *  and inline JS downstream. */
        get("/api/library/drawer") {
            val ids = parseIds(call.request.queryParameters["ids"] ?: "")
            val tracks = ids.mapNotNull(store::track)
            if (tracks.isEmpty()) return@get call.respondText("", ContentType.Text.Html)
            call.respondText(DrawerViews.optionsDrawer(store, tracks, idsParam(ids), queueCtx()), ContentType.Text.Html)
        }

        /** Drawer sub-sheets: add-to-playlist, set-as, info, delete. */
        get("/api/library/sub") {
            val kind = call.request.queryParameters["kind"] ?: ""
            val ids = parseIds(call.request.queryParameters["ids"] ?: "")
            val tracks = ids.mapNotNull(store::track)
            if (tracks.isEmpty()) return@get call.respondText("", ContentType.Text.Html)
            val infoSize = if (kind == "info") host.fileSize(tracks.first()) else -1L
            call.respondText(
                DrawerViews.subSheet(kind, store, tracks, idsParam(ids), queueCtx(), infoSize),
                ContentType.Text.Html
            )
        }

        // ---------- batch track actions (single ⋯ or multi-select) ----------

        post("/api/tracks/play-now") {
            val tracks = idList().mapNotNull(store::track)
            if (tracks.isEmpty()) return@post toast("Nothing to play")
            val source = if (tracks.size == 1) sourceLabel(store, queueCtx()) else "Selection"
            player.setQueue(tracks, 0, shuffled = false, source = source)
            toast(if (tracks.size == 1) "Playing now" else "${tracks.size} songs playing now")
        }

        post("/api/tracks/play-next") {
            val tracks = idList().mapNotNull(store::track)
            if (tracks.isEmpty()) return@post noContent()
            // playNext inserts after the current track, so add in reverse to keep order.
            tracks.asReversed().forEach(player::playNext)
            toast(if (tracks.size == 1) "Playing next: ${tracks.first().title}" else "${tracks.size} songs play next")
        }

        post("/api/tracks/add-queue") {
            val tracks = idList().mapNotNull(store::track)
            if (tracks.isEmpty()) return@post noContent()
            tracks.forEach(player::addToQueue)
            toast(if (tracks.size == 1) "Added to queue: ${tracks.first().title}" else "Added ${tracks.size} to queue")
        }

        post("/api/tracks/add-playlists") {
            val ids = idList()
            val pids = parseIds(call.request.queryParameters["pids"] ?: "")
            if (ids.isEmpty() || pids.isEmpty()) return@post toast("No playlist selected")
            pids.forEach { pid -> ids.forEach { tid -> store.addToPlaylist(pid, tid) } }
            val songWord = if (ids.size == 1) "song" else "songs"
            val plWord = if (pids.size == 1) "playlist" else "playlists"
            toast("Added ${ids.size} $songWord to ${pids.size} $plWord")
        }

        /** Hand the selection to the system share sheet, where there is one. */
        post("/api/tracks/share") {
            val ids = idList().filter { store.track(it) != null }
            if (ids.isEmpty()) return@post toast("Nothing to share")
            val requester = host.shareRequester ?: return@post toast("Sharing is unavailable here")
            requester(ids)
            noContent()
        }

        post("/api/tracks/delete") {
            val ids = idList()
            if (ids.isEmpty()) return@post noContent()
            // Physically delete the file; only drop rows for files that were
            // actually removed, so the library stays honest.
            val deletedIds = mutableListOf<Long>()
            var failed = 0
            ids.forEach { id ->
                val t = store.track(id) ?: return@forEach
                if (host.deleteFile(t)) {
                    art.evict(id)
                    store.removeTrack(id)
                    deletedIds += id
                } else failed++
            }
            // A deleted file must not linger in the play queue.
            player.removeTracksFromQueue(deletedIds)
            val deleted = deletedIds.size
            val msg = when {
                deleted == 0 -> "Couldn't delete ${if (ids.size == 1) "the file" else "the files"}"
                failed == 0 -> if (deleted == 1) "Deleted 1 file from device" else "Deleted $deleted files from device"
                else -> "Deleted $deleted, $failed failed"
            }
            refresh(msg)
        }

        /**
         * Sort endpoint fired by the pastel sort drawer. Maps the drawer's type
         * slug to that tab's sort key and persists it. Songs answer with the
         * re-sorted first page for #song-list; the grouped tabs re-render the
         * whole library screen, since their sort reorders tiles rather than a
         * list fragment.
         */
        get("/api/library/sort") {
            val tab = sortTab()
            val sort = LibrarySort.keyForSlug(tab, call.request.queryParameters["type"] ?: "")
            LibrarySort.write(store, tab, sort)
            if (tab == "songs") {
                val q = call.request.queryParameters["q"] ?: ""
                call.respondText(songPage(store, QueueCtx("songs", q, sort), 0), ContentType.Text.Html)
            } else {
                store.setSetting("lib_tab", tab)
                call.respondText(LibraryViews.libraryScreen(store, tab, "", sort), ContentType.Text.Html)
            }
        }

        post("/api/library/scan") {
            if (store.folders().isEmpty()) {
                call.response.header("HX-Trigger", """{"poet-toast":"Add a music folder first"}""")
                call.respondText(SettingsViews.scanCard(store, deps.scanner), ContentType.Text.Html)
                return@post
            }
            deps.scanner.startScan()
            call.respondText(SettingsViews.scanCard(store, deps.scanner), ContentType.Text.Html)
        }

        /** Automatic-rescan switch; re-arms the platform's folder watcher. */
        post("/api/library/auto-scan") {
            deps.scanner.setAutoScan(call.request.queryParameters["on"] == "1")
            call.respondText(SettingsViews.scanCard(store, deps.scanner), ContentType.Text.Html)
        }

        post("/api/library/scan-interval") {
            call.request.queryParameters["h"]?.toIntOrNull()
                ?.takeIf { it in ScanPort.INTERVAL_CHOICES }
                ?.let { deps.scanner.setIntervalHours(it) }
            call.respondText(SettingsViews.scanCard(store, deps.scanner), ContentType.Text.Html)
        }

        // ---------- tracks ----------

        post("/api/track/{id}/favorite") {
            val t = call.parameters["id"]?.toLongOrNull()?.let(store::track) ?: return@post noContent()
            store.setFavorite(t.id, !t.favorite)
            refresh(if (t.favorite) "Removed from favorites" else "Added to favorites")
        }

        get("/api/library/edit-tags/{id}") {
            val t = call.parameters["id"]?.toLongOrNull()?.let(store::track)
                ?: return@get call.respondText("", ContentType.Text.Html)
            // Opening the editor clears any leftover cover pick and reads the
            // file-only fields (comment, embedded lyrics) for prefill.
            deps.tags.clearPendingArt()
            val extras = deps.tags.readFileExtras(t)
            val isCurrent = player.snapshot.trackId == t.id
            call.respondText(
                TagEditorViews.tagEditorSheet(t, extras, isCurrent, deps.tags.writesToFile(t)),
                ContentType.Text.Html
            )
        }

        put("/api/library/edit-tags/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@put noContent()
            val p = call.receiveParameters()
            val form = TagForm(
                title = p["title"] ?: "", artist = p["artist"] ?: "", album = p["album"] ?: "",
                albumArtist = p["albumArtist"] ?: "", genre = p["genre"] ?: "", year = p["year"] ?: "",
                trackNo = p["trackNo"] ?: "", discNo = p["discNo"] ?: "", composer = p["composer"] ?: "",
                comment = p["comment"] ?: "", lyrics = p["lyrics"] ?: "",
                artAction = p["artAction"] ?: "keep",
                rename = p["rename"] == "1", renamePattern = p["renamePattern"] ?: ""
            )
            val result = deps.tags.saveTags(id, form)
            if (result.artChanged) {
                art.evict(id)
                host.onLibraryChanged()
            }
            val artEvent = if (result.artChanged) ""","poet-art-changed":$id""" else ""
            call.response.header(
                "HX-Trigger",
                """{"poet-toast":${jsonStr(result.message)},"poet-close-modal":true,"poet-refresh":true$artEvent}"""
            )
            call.respond(HttpStatusCode.NoContent)
        }

        // ---------- batch tag editing ----------

        get("/api/library/batch-tags") {
            val ids = parseIds(call.request.queryParameters["ids"] ?: "")
            val tracks = ids.mapNotNull(store::track)
            if (tracks.size < 2) return@get call.respondText("", ContentType.Text.Html)
            val writable = tracks.count { deps.tags.writesToFile(it) }
            call.respondText(
                TagEditorViews.batchTagEditorSheet(tracks, idsParam(ids), writable),
                ContentType.Text.Html
            )
        }

        put("/api/library/batch-tags") {
            val ids = parseIds(call.request.queryParameters["ids"] ?: "")
            if (ids.isEmpty()) return@put noContent()
            val p = call.receiveParameters()
            val form = BatchTagForm(
                title = p["title"] ?: "", artist = p["artist"] ?: "", album = p["album"] ?: "",
                albumArtist = p["albumArtist"] ?: "", genre = p["genre"] ?: "",
                year = p["year"] ?: "", trackNo = p["trackNo"] ?: ""
            )
            if (form.isEmpty()) {
                call.response.header("HX-Trigger", """{"poet-toast":"Fill in at least one field"}""")
                return@put call.respond(HttpStatusCode.NoContent)
            }
            val result = deps.tags.applyBatch(ids, form)
            // A batch write may rename nothing but does change titles the queue
            // is showing; refresh the screen and drop stale covers.
            ids.forEach(art::evict)
            host.onLibraryChanged()
            call.response.header(
                "HX-Trigger",
                """{"poet-toast":${jsonStr(result.message)},"poet-close-modal":true,"poet-refresh":true}"""
            )
            call.respond(HttpStatusCode.NoContent)
        }

        /** Fire the native image picker; the pick lands in TagPort.pendingArt. */
        post("/api/tageditor/pick-art") {
            val requester = host.pickArtRequester
            if (requester == null) toast("Image picker unavailable") else { requester(); noContent() }
        }

        /** Serves the cover image the user just picked, before it is saved. */
        get("/api/tageditor/art-preview") {
            val pending = deps.tags.pendingArt ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.header("Cache-Control", "no-store")
            call.respondBytes(pending, ContentType.parse(deps.tags.pendingArtMime))
        }

        /** Export the synced-lyrics LRC built in the editor to a sidecar file. */
        post("/api/tageditor/{id}/save-lrc") {
            val t = call.parameters["id"]?.toLongOrNull()?.let(store::track) ?: return@post noContent()
            val lrc = call.receiveParameters()["lrc"] ?: ""
            toast(deps.tags.saveLrc(t, lrc).message)
        }

        post("/api/track/{id}/remove") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post noContent()
            store.removeTrack(id)
            refresh("Removed from library")
        }

        // ---------- playlists ----------

        post("/api/playlist/create") {
            val name = call.receiveParameters()["name"]?.trim().orEmpty()
            if (name.isEmpty()) return@post toast("Playlist needs a name")
            val pid = store.createPlaylist(name)
            // Optionally seed the new playlist with the drawer's target tracks.
            call.request.queryParameters["trackId"]?.toLongOrNull()?.let { store.addToPlaylist(pid, it) }
            parseIds(call.request.queryParameters["ids"] ?: "").forEach { store.addToPlaylist(pid, it) }
            refresh("Created playlist \"$name\"")
        }

        post("/api/playlist/{pid}/add/{tid}") {
            val pid = call.parameters["pid"]?.toLongOrNull() ?: return@post noContent()
            val tid = call.parameters["tid"]?.toLongOrNull() ?: return@post noContent()
            store.addToPlaylist(pid, tid)
            toast("Added to ${store.playlist(pid)?.name ?: "playlist"}")
        }

        post("/api/playlist/{pid}/remove/{tid}") {
            val pid = call.parameters["pid"]?.toLongOrNull() ?: return@post noContent()
            val tid = call.parameters["tid"]?.toLongOrNull() ?: return@post noContent()
            store.removeFromPlaylist(pid, tid)
            refresh("Removed from playlist")
        }

        post("/api/playlist/{pid}/delete") {
            val pid = call.parameters["pid"]?.toLongOrNull() ?: return@post noContent()
            store.deletePlaylist(pid)
            call.response.header(
                "HX-Trigger",
                """{"poet-toast":"Playlist deleted","poet-goto":"/screens/library?tab=playlists"}"""
            )
            call.respond(HttpStatusCode.NoContent)
        }

        // ---------- settings ----------

        post("/api/settings/add-folder") {
            val requester = host.addFolderRequester
            if (requester == null) toast("Folder picker unavailable") else { requester(); noContent() }
        }

        /** Fired by the OK button of the pastel folder-removal modal. */
        delete("/api/settings/folder") {
            val id = call.request.queryParameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.NoContent)
            store.removeFolder(id)
            call.response.header("HX-Trigger", """{"poet-toast":"Folder removed","poet-refresh":true}""")
            call.respond(HttpStatusCode.NoContent)
        }

        post("/api/settings/accent") {
            val c = call.receiveParameters()["c"] ?: return@post noContent()
            if (c.matches(Regex("#[0-9a-fA-F]{6}"))) {
                store.setSetting("accent", c)
                host.onLibraryChanged()
            }
            noContent()
        }

        post("/api/settings/theme") {
            val name = call.receiveParameters()["name"] ?: return@post noContent()
            if (name in Shell.CANVAS_TINTS) {
                store.setSetting("theme", name)
                host.onLibraryChanged()
            }
            noContent()
        }

        // ---------- equalizer ----------

        /* Every effect route answers with the whole re-rendered card: a preset
           moves every band at once, and enabling the chain restyles the
           sliders, so a partial swap would go stale. */

        post("/api/eq/enabled") {
            deps.eq.setEnabled(call.request.queryParameters["on"] == "1")
            call.respondText(SettingsViews.equalizerCard(deps.eq), ContentType.Text.Html)
        }

        post("/api/eq/preset") {
            call.request.queryParameters["name"]?.let(deps.eq::applyPreset)
            call.respondText(SettingsViews.equalizerCard(deps.eq), ContentType.Text.Html)
        }

        post("/api/eq/band") {
            val index = call.request.queryParameters["i"]?.toIntOrNull()
            val level = call.receiveParameters()["level"]?.toIntOrNull()
            if (index != null && level != null) deps.eq.setBand(index, level)
            call.respondText(SettingsViews.equalizerCard(deps.eq), ContentType.Text.Html)
        }

        post("/api/eq/bass") {
            call.receiveParameters()["v"]?.toIntOrNull()?.let(deps.eq::setBassStrength)
            call.respondText(SettingsViews.equalizerCard(deps.eq), ContentType.Text.Html)
        }

        post("/api/eq/virtualizer") {
            call.receiveParameters()["v"]?.toIntOrNull()?.let(deps.eq::setVirtualizerStrength)
            call.respondText(SettingsViews.equalizerCard(deps.eq), ContentType.Text.Html)
        }

        /** Previous-button restart threshold: seconds, 0 = never restart. */
        post("/api/settings/prev-restart") {
            val sec = call.request.queryParameters["sec"]?.toIntOrNull()?.coerceIn(0, 30) ?: 3
            player.setPrevRestartMs(sec * 1000L)
            call.respondText(settingsScreen(deps, false), ContentType.Text.Html)
        }

        post("/api/settings/dark") {
            val on = call.receiveParameters()["on"] == "1"
            store.setSetting("dark", if (on) "1" else "0")
            noContent()
        }

        post("/api/widget/pin") {
            val requester = host.pinWidgetRequester
            if (requester == null) toast("Widget pinning is unavailable here")
            else {
                requester.invoke()
                noContent()
            }
        }

        // ---------- listening journal ----------

        /** Fire the native image picker for the journal portrait. */
        post("/api/journal/pick-portrait") {
            val requester = host.pickPortraitRequester
            if (requester == null) toast("Image picker unavailable") else { requester(); noContent() }
        }

        get("/api/journal/portrait") {
            val bytes = host.portraitBytes() ?: return@get call.respond(HttpStatusCode.NotFound)
            // The ?v= stamp changes on every replacement, so the image may be
            // cached hard until the portrait actually changes.
            call.response.header("Cache-Control", "max-age=3600")
            call.respondBytes(bytes, ContentType.parse(host.portraitMime()))
        }

        // ---------- media ----------

        get("/api/art/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.NotFound)
            when (val result = art.get(id, store.track(id))) {
                is ArtCache.Result.Jpeg -> {
                    call.response.header("Cache-Control", "max-age=3600")
                    call.respondBytes(result.bytes, ContentType.parse("image/jpeg"))
                }
                is ArtCache.Result.Svg -> {
                    call.response.header("Cache-Control", "max-age=3600")
                    call.respondText(result.markup, ContentType.parse("image/svg+xml"))
                }
            }
        }

        /** Write this track's cover into the device gallery, where there is one. */
        post("/api/art/{id}/save") {
            val t = call.parameters["id"]?.toLongOrNull()?.let(store::track)
                ?: return@post toast("Track not found")
            if (!t.hasArt) return@post toast("This song has no embedded artwork")
            val requester = host.saveArtRequester ?: return@post toast("Saving is unavailable here")
            requester(t.id)
            noContent()
        }

        get("/api/stream/{id}") {
            val track = call.parameters["id"]?.toLongOrNull()?.let(store::track)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val input = host.openAudioStream(track) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondOutputStream(ContentType.parse(audioMime(track.displayName))) {
                input.use { it.copyTo(this) }
            }
        }
    }
}

// ---------- route helpers ----------

/** The Settings screen, with the four ports it reads from. */
private fun settingsScreen(deps: PoetDeps, showTip: Boolean): String =
    SettingsViews.settingsScreen(deps.store, deps.eq, deps.scanner, deps.player.prevRestartMs, showTip)

/** Re-rendered inner content of the queue panel (#qp-body). */
private fun queueBody(deps: PoetDeps): String =
    QueueViews.queuePanelBody(deps.store, deps.player.queueItems(), deps.player.snapshot.playing)

/** Human label for the listing a queue was built from ("Playing from …"). */
private fun sourceLabel(store: LibraryStore, ctx: QueueCtx): String = when (ctx.ctx) {
    "album" -> ctx.album.ifBlank { "Album" }
    "artist" -> ctx.artist.ifBlank { "Artist" }
    "genre" -> ctx.genre.ifBlank { "Genre" }
    "playlist" -> store.playlist(ctx.pid)?.name ?: "Playlist"
    "favorites" -> "Favorites"
    else -> if (ctx.q.isNotBlank()) "search “${ctx.q}”" else "All songs"
}

/** The library tab a sort request applies to; unknown tabs mean Songs. */
private fun PipelineContext<Unit, ApplicationCall>.sortTab(): String {
    val tab = call.request.queryParameters["tab"] ?: "songs"
    return if (tab in LibrarySort.SORTABLE_TABS) tab else "songs"
}

/** First (or nth) page of a tracklist, with the total behind it. */
private fun songPage(store: LibraryStore, ctx: QueueCtx, offset: Int): String =
    SharedViews.songList(ctx.resolvePage(store, offset, SharedViews.PAGE_SIZE), ctx, offset, ctx.total(store))

/** Comma-separated ids from the "ids" query parameter. */
private fun PipelineContext<Unit, ApplicationCall>.idList(): List<Long> =
    parseIds(call.request.queryParameters["ids"] ?: "")

private fun PipelineContext<Unit, ApplicationCall>.queueCtx(): QueueCtx {
    val p = call.request.queryParameters
    return QueueCtx(
        ctx = p["ctx"] ?: "songs",
        q = p["q"] ?: "",
        sort = p["sort"] ?: "title",
        album = p["album"] ?: "",
        artist = p["artist"] ?: "",
        pid = p["pid"]?.toLongOrNull() ?: 0,
        genre = p["genre"] ?: ""
    )
}

private suspend fun PipelineContext<Unit, ApplicationCall>.noContent() {
    call.respond(HttpStatusCode.NoContent)
}

private suspend fun PipelineContext<Unit, ApplicationCall>.toast(msg: String) {
    call.response.header("HX-Trigger", """{"poet-toast":${jsonStr(msg)}}""")
    call.respond(HttpStatusCode.NoContent)
}

/** Accent-colored pill toast, used for Now Playing state changes. */
private suspend fun PipelineContext<Unit, ApplicationCall>.toastAccent(msg: String) {
    call.response.header("HX-Trigger", """{"poet-toast-accent":${jsonStr(msg)}}""")
    call.respond(HttpStatusCode.NoContent)
}

private suspend fun PipelineContext<Unit, ApplicationCall>.refresh(msg: String) {
    call.response.header("HX-Trigger", """{"poet-toast":${jsonStr(msg)},"poet-refresh":true}""")
    call.respond(HttpStatusCode.NoContent)
}

/**
 * Album art, extracted once and kept in an LRU bounded by *bytes* rather than
 * entry count, so a run of high-res covers cannot balloon the heap.
 *
 * Platform-independent, and both builds want it: extraction is the expensive
 * part on a phone (MediaMetadataRetriever) and on a desktop (re-reading a FLAC
 * picture block) alike.
 */
internal class ArtCache(private val host: HostPort) {

    sealed interface Result {
        class Jpeg(val bytes: ByteArray) : Result
        class Svg(val markup: String) : Result
    }

    private val maxBytes = 12 * 1024 * 1024
    private var bytes = 0L
    private val entries = LinkedHashMap<Long, ByteArray>(16, 0.75f, true)

    /** Branded cover shown for tracks with no embedded artwork. */
    private val placeholder: ByteArray? by lazy { host.asset("placeholder.jpg") }

    /** Drop a track's cached cover so the next request re-extracts it. */
    fun evict(id: Long) = synchronized(entries) {
        entries.remove(id)?.let { bytes -= it.size }
        Unit
    }

    private fun put(id: Long, art: ByteArray) = synchronized(entries) {
        // Oversized covers would evict everything else; serve them uncached.
        if (art.size > maxBytes / 4) return@synchronized
        entries.remove(id)?.let { bytes -= it.size }
        entries[id] = art
        bytes += art.size
        val eldest = entries.entries.iterator()
        while (bytes > maxBytes && eldest.hasNext()) {
            bytes -= eldest.next().value.size
            eldest.remove()
        }
    }

    fun get(id: Long, track: Track?): Result {
        synchronized(entries) { entries[id] }?.let { return Result.Jpeg(it) }
        val art = if (track != null && track.hasArt) host.embeddedArt(track) else null
        if (art != null) {
            put(id, art)
            return Result.Jpeg(art)
        }
        placeholder?.let { return Result.Jpeg(it) }
        val bg = artColor(id)
        val label = esc(initials(track?.title ?: "?"))
        return Result.Svg(
            """<svg xmlns="http://www.w3.org/2000/svg" width="240" height="240">""" +
                """<rect width="240" height="240" fill="$bg"/>""" +
                """<text x="120" y="136" font-family="sans-serif" font-size="52" font-weight="600" fill="rgba(59,54,81,0.5)" text-anchor="middle">$label</text></svg>"""
        )
    }
}
