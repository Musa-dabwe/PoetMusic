package com.musa.poetmusic.server

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.musa.poetmusic.data.LibraryScanner
import com.musa.poetmusic.data.LrcParser
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.data.TagEditor
import com.musa.poetmusic.playback.PlayerController
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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

    private var started = false
    private val artCache = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 24
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
                    call.respondText(Shell.page(accent, theme, db.folders().size), ContentType.Text.Html)
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
                    val tab = call.request.queryParameters["tab"] ?: "songs"
                    val q = call.request.queryParameters["q"] ?: ""
                    val sort = call.request.queryParameters["sort"] ?: "title"
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
                    val sort = call.request.queryParameters["sort"] ?: "title"
                    val ctx = QueueCtx("songs", q, sort)
                    call.respondText(Views.songList(db.tracks(q, sort), ctx), ContentType.Text.Html)
                }

                get("/partial/scan") {
                    call.respondText(Views.scanCard(db), ContentType.Text.Html)
                }

                get("/partial/sleep-menu") {
                    call.respondText(Views.sleepMenu(), ContentType.Text.Html)
                }

                // ---------- player API ----------

                get("/api/player/state") {
                    val s = PlayerController.snapshot
                    val json = """{"trackId":${s.trackId},"title":${jsonStr(s.title)},"artist":${jsonStr(s.artist)},""" +
                        """"pos":${s.positionMs},"dur":${s.durationMs},"playing":${s.playing},"shuffle":${s.shuffle},""" +
                        """"repeat":${s.repeatMode},"speed":${s.speed},"sleep":${s.sleepRemainingMs},"hasQueue":${s.hasQueue}}"""
                    call.respondText(json, ContentType.Application.Json)
                }

                post("/api/player/play/{id}") {
                    val id = call.parameters["id"]?.toLongOrNull() ?: return@post noContent()
                    val ctx = queueCtx()
                    val tracks = ctx.resolve(db)
                    val index = tracks.indexOfFirst { it.id == id }
                    if (index >= 0) PlayerController.setQueue(tracks, index, shuffled = false)
                    else db.track(id)?.let { PlayerController.setQueue(listOf(it), 0, shuffled = false) }
                    noContent()
                }

                post("/api/queue/replace") {
                    val ctx = queueCtx()
                    val shuffle = call.request.queryParameters["shuffle"] == "1"
                    val tracks = ctx.resolve(db)
                    if (tracks.isEmpty()) return@post toast("Nothing to play yet")
                    val start = if (shuffle) tracks.indices.random() else 0
                    PlayerController.setQueue(tracks, start, shuffled = shuffle)
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

                post("/api/player/toggle") { PlayerController.togglePlay(); noContent() }
                post("/api/player/next") { PlayerController.next(); noContent() }
                post("/api/player/prev") { PlayerController.previous(); noContent() }
                post("/api/player/shuffle") { PlayerController.toggleShuffle(); noContent() }
                post("/api/player/repeat") { PlayerController.cycleRepeat(); noContent() }
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

                get("/api/library/menu/{id}") {
                    val t = call.parameters["id"]?.toLongOrNull()?.let(db::track)
                        ?: return@get call.respondText("", ContentType.Text.Html)
                    call.respondText(Views.contextMenu(db, t, queueCtx()), ContentType.Text.Html)
                }

                get("/api/library/menu/{id}/playlists") {
                    val t = call.parameters["id"]?.toLongOrNull()?.let(db::track)
                        ?: return@get call.respondText("", ContentType.Text.Html)
                    call.respondText(Views.playlistSubmenu(db, t, queueCtx()), ContentType.Text.Html)
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

                get("/api/track/{id}/tags") {
                    val t = call.parameters["id"]?.toLongOrNull()?.let(db::track)
                        ?: return@get call.respondText("", ContentType.Text.Html)
                    call.respondText(Views.tagEditorModal(t), ContentType.Text.Html)
                }

                post("/api/track/{id}/tags") {
                    val id = call.parameters["id"]?.toLongOrNull() ?: return@post noContent()
                    val p = call.receiveParameters()
                    val result = TagEditor.saveTags(
                        app, db, id,
                        p["title"] ?: "", p["artist"] ?: "", p["album"] ?: ""
                    )
                    call.response.header(
                        "HX-Trigger",
                        """{"poet-toast":${jsonStr(result.message)},"poet-close-modal":true,"poet-refresh":true}"""
                    )
                    call.respond(HttpStatusCode.NoContent)
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
                    call.request.queryParameters["trackId"]?.toLongOrNull()?.let { db.addToPlaylist(pid, it) }
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

                post("/api/settings/remove-folder/{id}") {
                    val id = call.parameters["id"]?.toLongOrNull() ?: return@post noContent()
                    db.removeFolder(id)
                    refresh("Folder removed")
                }

                post("/api/settings/accent") {
                    val c = call.receiveParameters()["c"] ?: return@post noContent()
                    if (c.matches(Regex("#[0-9a-fA-F]{6}"))) db.setSetting("accent", c)
                    noContent()
                }

                post("/api/settings/theme") {
                    val name = call.receiveParameters()["name"] ?: return@post noContent()
                    if (name in Shell.CANVAS_TINTS) db.setSetting("theme", name)
                    noContent()
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
                        synchronized(artCache) { artCache[id] = art }
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
