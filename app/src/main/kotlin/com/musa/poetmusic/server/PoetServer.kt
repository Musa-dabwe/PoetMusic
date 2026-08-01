package com.musa.poetmusic.server

import android.content.Context
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.playback.PlayerController
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

/**
 * The embedded server on Android.
 *
 * The routing table itself lives in `:core` (`poetRoutes`) and is shared
 * byte-for-byte with the Linux desktop build — see
 * docs/desktop-app-plan.md §2.3. All that is left here is constructing the
 * Android implementations of the ports and binding the socket.
 *
 * The bind is `127.0.0.1:8080` and must stay loopback-only: the README, the
 * About screen and SECURITY.md all state that the server is unreachable from
 * other devices, and that is the property this line carries.
 */
object PoetServer {

    private var started = false

    /**
     * The native affordances `MainActivity` owns — the SAF folder picker, the
     * gallery pickers, the share sheet, widget pinning. Exposed here so the
     * Activity can install and clear them exactly as it did before the port.
     */
    lateinit var host: AndroidHost
        private set

    @Synchronized
    fun start(context: Context, db: MusicDatabase) {
        if (started) return
        started = true

        host = AndroidHost(context, db)
        val deps = PoetDeps(
            store = db,
            player = PlayerController,
            eq = AndroidEq(db),
            scanner = AndroidScanner(context, db),
            tags = AndroidTags(context, db),
            host = host,
            about = ANDROID_ABOUT
        )

        embeddedServer(CIO, port = 8080, host = "127.0.0.1") {
            poetRoutes(deps)
        }.start(wait = false)
    }
}
