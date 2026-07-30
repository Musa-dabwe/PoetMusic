package com.musa.poetmusic.server

import android.content.Context
import com.musa.poetmusic.data.MusicDatabase
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing

/**
 * Embedded Ktor server bound to the app process on localhost:8080.
 * Serves the htmx frontend and the playback/library API.
 *
 * The handlers themselves live in the `Routes*.kt` files, one group per
 * concern; this object only owns the engine, the native capability hooks
 * MainActivity fills in, and the order the groups are registered in.
 */
object PoetServer {

    /** Set by MainActivity so the frontend can open the SAF folder picker. */
    @Volatile var addFolderRequester: (() -> Unit)? = null

    /** Set by MainActivity so the frontend can request pinning the home screen widget. */
    @Volatile var pinWidgetRequester: (() -> Unit)? = null

    /** Set by MainActivity so the tag editor can open the gallery image picker. */
    @Volatile var pickArtRequester: (() -> Unit)? = null

    private var started = false

    @Synchronized
    fun start(context: Context, db: MusicDatabase) {
        if (started) return
        started = true
        val app = context.applicationContext
        ArtCache.loadPlaceholder(app.assets)

        embeddedServer(CIO, port = 8080, host = "127.0.0.1") {
            routing {
                // Registration order matches the original single-block server.
                pageRoutes(app, db)
                playerRoutes(app, db)
                libraryRoutes(app, db)
                tagEditorRoutes(app, db)
                playlistRoutes(db)
                settingsRoutes(app, db)
                mediaRoutes(app, db)
            }
        }.start(wait = false)
    }
}
