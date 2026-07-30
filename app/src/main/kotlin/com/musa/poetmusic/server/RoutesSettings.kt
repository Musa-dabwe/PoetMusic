package com.musa.poetmusic.server

import android.content.Context
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.widget.WidgetRenderer
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post

/** Compiled once rather than per request. */
private val RE_HEX_COLOR = Regex("#[0-9a-fA-F]{6}")

/**
 * Folder management, theming, and widget pinning.
 *
 * Accent and theme writes push the widget as well, so a recolor lands on the
 * home screen without waiting for the next playback event.
 */
internal fun Route.settingsRoutes(app: Context, db: MusicDatabase) {

    post("/api/settings/add-folder") {
        val requester = PoetServer.addFolderRequester
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
        if (c.matches(RE_HEX_COLOR)) {
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
        val requester = PoetServer.pinWidgetRequester
        if (requester == null) toast("Widget pinning is unavailable right now")
        else {
            requester.invoke()
            noContent()
        }
    }
}
