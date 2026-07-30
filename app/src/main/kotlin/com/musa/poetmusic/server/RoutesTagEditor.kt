package com.musa.poetmusic.server

import android.content.Context
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.data.TagEditor
import com.musa.poetmusic.playback.PlayerController
import com.musa.poetmusic.widget.WidgetRenderer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/**
 * The tag editor sheet: prefill, save, the cover pick that round-trips
 * through the native gallery picker, and LRC export.
 *
 * `/api/library/edit-tags/{id}` keeps its historical path even though it
 * belongs to this group rather than the library drawer.
 */
internal fun Route.tagEditorRoutes(app: Context, db: MusicDatabase) {

    get("/api/library/edit-tags/{id}") {
        val t = trackParam(db)
            ?: return@get emptyHtml()
        // Opening the editor clears any leftover cover pick and reads
        // the file-only fields (comment, embedded lyrics) for prefill.
        TagEditor.clearPendingArt()
        val extras = TagEditor.readFileExtras(app, t)
        val isCurrent = PlayerController.snapshot.trackId == t.id
        html(TagEditorViews.tagEditorSheet(t, extras, isCurrent))
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
            ArtCache.evict(id)
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
        val requester = PoetServer.pickArtRequester
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
        val t = trackParam(db) ?: return@post noContent()
        val lrc = call.receiveParameters()["lrc"] ?: ""
        val result = TagEditor.saveLrc(app, db, t, lrc)
        toast(result.message)
    }
}
