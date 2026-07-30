package com.musa.poetmusic.server

import com.musa.poetmusic.data.MusicDatabase
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** Playlist create / add / remove / delete. */
internal fun Route.playlistRoutes(db: MusicDatabase) {

    post("/api/playlist/create") {
        val name = call.receiveParameters()["name"]?.trim().orEmpty()
        if (name.isEmpty()) return@post toast("Playlist needs a name")
        val pid = db.createPlaylist(name)
        // Optionally seed the new playlist with the drawer's target tracks.
        call.request.queryParameters["trackId"]?.toLongOrNull()?.let { db.addToPlaylist(pid, it) }
        idList().forEach { db.addToPlaylist(pid, it) }
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
}
