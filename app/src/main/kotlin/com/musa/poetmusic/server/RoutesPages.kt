package com.musa.poetmusic.server

import android.content.Context
import com.musa.poetmusic.data.AppSettings
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.playback.PlayerController
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Compiled once rather than per request. */
private val RE_ASSET_NAME = Regex("[A-Za-z0-9._-]+")

/**
 * Everything the WebView loads directly: the page shell, the bundled assets,
 * the full-screen swaps into `#main-container`, and the fragments that fill
 * the overlay roots.
 */
internal fun Route.pageRoutes(app: Context, db: MusicDatabase) {

    get("/") {
        html(Shell.page(AppSettings.from(db), db.folders().size))
    }

    get("/assets/{name}") {
        val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.NotFound)
        if (!name.matches(RE_ASSET_NAME)) return@get call.respond(HttpStatusCode.NotFound)
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
        html(LibraryViews.libraryScreen(db, tab, q, sort))
    }

    get("/screens/now-playing") {
        val lyricsOpen = call.request.queryParameters["lyrics"] == "1"
        html(NowPlayingViews.nowPlayingScreen(db, lyricsOpen))
    }

    get("/screens/settings") {
        val tip = call.request.queryParameters["tip"] == "1"
        html(SettingsViews.settingsScreen(db, tip))
    }

    get("/screens/about") {
        html(SettingsViews.aboutScreen(db))
    }

    get("/screens/album") {
        val album = call.request.queryParameters["album"] ?: ""
        val artist = call.request.queryParameters["artist"] ?: ""
        html(LibraryViews.albumScreen(db, album, artist))
    }

    get("/screens/artist") {
        val name = call.request.queryParameters["name"] ?: ""
        html(LibraryViews.artistScreen(db, name))
    }

    get("/screens/favorites") {
        html(LibraryViews.favoritesScreen(db))
    }

    get("/screens/playlist/{id}") {
        val id = call.parameters["id"]?.toLongOrNull() ?: 0
        html(LibraryViews.playlistScreen(db, id))
    }

    // ---------- partials ----------

    get("/partial/songs") {
        val q = call.request.queryParameters["q"] ?: ""
        val sort = call.request.queryParameters["sort"]?.also { db.setSetting("lib_sort", it) }
            ?: db.getSetting("lib_sort", "title")
        val ctx = QueueCtx("songs", q, sort)
        html(SharedViews.songList(db.tracks(q, sort), ctx))
    }

    get("/partial/queue") {
        val items = PlayerController.queueItems()
        html(QueueViews.queuePanel(db, items, PlayerController.snapshot.playing))
    }

    get("/partial/queue-body") {
        html(queueBody(db))
    }

    get("/partial/scan") {
        html(SettingsViews.scanCard(db))
    }

    get("/partial/sleep-menu") {
        html(NowPlayingViews.sleepDrawer())
    }

    get("/partial/sort-drawer") {
        html(LibraryViews.sortDrawer(db.getSetting("lib_sort", "title")))
    }

    /** Empty fragment: swapped into overlay roots to close them. */
    get("/partial/empty") {
        emptyHtml()
    }

    get("/partial/confirm-folder/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
        val folder = db.folders().firstOrNull { it.id == id }
            ?: return@get emptyHtml()
        html(DrawerViews.confirmRemoveFolder(folder.id, folder.displayPath))
    }

    get("/partial/confirm-track/{id}") {
        val t = trackParam(db)
            ?: return@get emptyHtml()
        html(DrawerViews.confirmRemoveTrack(t))
    }

    get("/partial/confirm-playlist/{id}") {
        val pl = call.parameters["id"]?.toLongOrNull()?.let(db::playlist)
            ?: return@get emptyHtml()
        html(DrawerViews.confirmDeletePlaylist(pl))
    }
}
