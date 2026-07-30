package com.musa.poetmusic.server

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.musa.poetmusic.data.LibraryScanner
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.playback.PlayerController
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * The options drawer and its sub-sheets, the batch actions they fire, and
 * library-wide operations (sort, scan).
 *
 * The batch routes all take an `ids` list, so a single-track `⋯` menu and a
 * multi-select both land on the same handler.
 */
internal fun Route.libraryRoutes(app: Context, db: MusicDatabase) {

    /** Full options drawer for one track (single ⋯) or a batch selection.
     *  The ids string handed to the view is rebuilt from the parsed
     *  numeric list, never the raw query value: it is interpolated
     *  into attributes and inline JS downstream. */
    get("/api/library/drawer") {
        val ids = idList()
        val tracks = ids.mapNotNull(db::track)
        if (tracks.isEmpty()) return@get emptyHtml()
        html(DrawerViews.optionsDrawer(db, tracks, idsParam(ids), queueCtx()))
    }

    /** Drawer sub-sheets: add-to-playlist, set-as, info, delete. */
    get("/api/library/sub") {
        val kind = call.request.queryParameters["kind"] ?: ""
        val ids = idList()
        val tracks = ids.mapNotNull(db::track)
        if (tracks.isEmpty()) return@get emptyHtml()
        val infoSize = if (kind == "info") fileSize(app, tracks.first().uri) else -1L
        html(DrawerViews.subSheet(kind, db, tracks, idsParam(ids), queueCtx(), infoSize))
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

    post("/api/tracks/add-playlists") {
        val ids = idList()
        val pids = idList("pids")
        if (ids.isEmpty() || pids.isEmpty()) return@post toast("No playlist selected")
        pids.forEach { pid -> ids.forEach { tid -> db.addToPlaylist(pid, tid) } }
        val songWord = if (ids.size == 1) "song" else "songs"
        val plWord = if (pids.size == 1) "playlist" else "playlists"
        toast("Added ${ids.size} $songWord to ${pids.size} $plWord")
    }

    post("/api/tracks/delete") {
        val ids = idList()
        if (ids.isEmpty()) return@post noContent()
        // Physically delete the file through SAF; only drop rows for
        // files that were actually removed so the library stays honest.
        val deletedIds = mutableListOf<Long>()
        var failed = 0
        ids.forEach { id ->
            val t = db.track(id) ?: return@forEach
            val ok = try {
                DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(t.uri))
            } catch (e: Exception) {
                false
            }
            if (ok) {
                ArtCache.evict(id)
                db.removeTrack(id)
                deletedIds += id
            } else failed++
        }
        // A deleted file must not linger in the play queue.
        PlayerController.removeTracksFromQueue(deletedIds)
        val deleted = deletedIds.size
        val msg = when {
            deleted == 0 -> "Couldn't delete ${if (ids.size == 1) "the file" else "the files"}"
            failed == 0 -> if (deleted == 1) "Deleted 1 file from device" else "Deleted $deleted files from device"
            else -> "Deleted $deleted, $failed failed"
        }
        refresh(msg)
    }

    /**
     * Sort endpoint fired by the pastel sort drawer. Maps the
     * drawer's type slug to a library sort key, persists it, and
     * returns the re-sorted tracklist for the #song-list target.
     */
    get("/api/library/sort") {
        val type = call.request.queryParameters["type"] ?: "title-az"
        val sort = LibraryViews.SORT_STATES.firstOrNull { it.first == type }?.second ?: "title"
        db.setSetting("lib_sort", sort)
        val q = call.request.queryParameters["q"] ?: ""
        val ctx = QueueCtx("songs", q, sort)
        html(SharedViews.songList(db.tracks(q, sort), ctx))
    }

    post("/api/library/scan") {
        if (db.folders().isEmpty()) {
            call.response.header("HX-Trigger", """{"poet-toast":"Add a music folder first"}""")
            html(SettingsViews.scanCard(db))
            return@post
        }
        LibraryScanner.startScan(app, db)
        html(SettingsViews.scanCard(db))
    }

    // ---------- single tracks ----------

    post("/api/track/{id}/favorite") {
        val t = trackParam(db) ?: return@post noContent()
        db.setFavorite(t.id, !t.favorite)
        refresh(if (t.favorite) "Removed from favorites" else "Added to favorites")
    }

    post("/api/track/{id}/remove") {
        val id = call.parameters["id"]?.toLongOrNull() ?: return@post noContent()
        db.removeTrack(id)
        refresh("Removed from library")
    }
}
