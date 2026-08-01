package com.musa.poetmusic.desktop

import com.musa.poetmusic.desktop.ui.WebKitWindow
import com.musa.poetmusic.server.AboutSpec
import com.musa.poetmusic.server.PoetDeps
import com.musa.poetmusic.server.poetRoutes
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.awt.Desktop
import java.net.ServerSocket
import java.net.URI
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Poet Music — the Linux desktop app (docs/desktop-app-plan.md).
 *
 * Same shape as the Android build: an embedded Ktor server bound to loopback,
 * serving the identical frontend assets and the identical routing table from
 * `:core`, rendered in a WebKit view inside a native GTK window instead of an
 * Android `WebView`.
 *
 * The `127.0.0.1`-only bind is a load-bearing security property carried over
 * from the phone — it is stated in the README and the About screen, and must
 * not become a wildcard bind here. Unlike the phone the port is chosen at
 * random rather than fixed at 8080: a desktop is multi-user, and a fixed port
 * collides.
 *
 * Startup order matters. GTK's main loop must own the process's first thread,
 * so the server, the store and the player are all built first and the window
 * is the last thing opened; `WebKitWindow.run` then blocks until it closes.
 */
object Main

private val APP_VERSION: String =
    Main::class.java.`package`?.implementationVersion ?: "dev"

private const val LOOPBACK = "127.0.0.1"

fun main(args: Array<String>) {
    quietenTagLibrary()
    val store = DesktopLibrary(DesktopLibrary.defaultDbFile())
    val player = GstPlayer(store)
    val eq = GstEq(store, player.equalizer)
    val scanner = DesktopScanner(store)
    val tags = DesktopTags(store, player::onTrackFileRenamed)
    val host = DesktopHost(store, tags) { scanner.startScan() }

    seedFirstRunFolder(store, args.firstOrNull())
    player.restoreState()
    scanner.maybeAutoScan()

    val port = freePort()
    val server = embeddedServer(CIO, port = port, host = LOOPBACK) {
        poetRoutes(
            PoetDeps(
                store = store,
                player = player,
                eq = eq,
                scanner = scanner,
                tags = tags,
                host = host,
                about = desktopAbout(port)
            )
        )
    }
    server.start(wait = false)

    val url = "http://$LOOPBACK:$port"
    println("Poet Music (desktop) $APP_VERSION")
    println("Serving on $url  —  library: ${DesktopLibrary.defaultDbFile()}")

    // Everything that has to be flushed before the process ends, in order.
    // Two things race to run it — the window's destroy handler and the JVM
    // shutdown hook — so the first caller wins and the second is a no-op;
    // tearing GStreamer down twice from two threads is not survivable.
    val shuttingDown = java.util.concurrent.atomic.AtomicBoolean(false)
    val mpris = MprisRef()
    val shutdown = {
        if (shuttingDown.compareAndSet(false, true)) {
            mpris.get()?.shutdown()
            player.shutdown()
            runCatching { server.stop(500, 1500) }
        }
        Unit
    }
    // Covers a kill from outside the window: Ctrl-C, a session logout, or the
    // SIGTERM the package's prerm sends before uninstalling. Registered before
    // anything else can ask to quit, so no exit path can miss it.
    Runtime.getRuntime().addShutdownHook(Thread(shutdown))

    /**
     * Flush and go. Deliberately [Runtime.halt] rather than a return from main:
     * once our own state is safe there is nothing to gain from the JVM's exit
     * sequence, and plenty to lose — it hands the process to GStreamer's and
     * WebKitGTK's native teardown, which abort. A SIGABRT is a core dump and,
     * on a desktop that collects them, a crash report for a clean quit.
     */
    val exitApp = {
        shutdown()
        Runtime.getRuntime().halt(0)
    }

    // Desktop media controls. Started before the window so a panel applet has
    // the restored track from the first frame; a machine with no session bus
    // simply does without, exactly as one with no GTK falls back to a browser.
    // Quit ends the process directly rather than closing the window, because
    // GTK ignores a close request for a window it has not realized yet — which
    // is most of the first second the app is alive.
    val media = MprisService(
        player = player,
        store = store,
        artFor = { track -> host.embeddedArt(track) ?: host.asset("placeholder.jpg") },
        onRaise = { WebKitWindow.present() },
        onQuit = exitApp
    )
    if (media.start()) {
        mpris.set(media)
        host.onLibraryChangedHook = media::publish
    }

    if (!WebKitWindow.run(url, "Poet Music", onClosed = shutdown)) {
        // No GTK or no WebKitGTK: fall back to the default browser rather than
        // failing to start, and keep the server alive for it.
        println("GTK/WebKitGTK unavailable — opening the default browser instead.")
        openBrowser(url)
        Thread.currentThread().join()
    }
    exitApp()
}

/**
 * Holds the MPRIS service for the shutdown lambda, which has to exist before it
 * does: the lambda is what the service's own Quit calls, and the shutdown hook
 * must be registered before anything can ask to quit at all.
 */
private class MprisRef {
    @Volatile private var service: MprisService? = null
    fun get(): MprisService? = service
    fun set(value: MprisService) { service = value }
}

/**
 * First run has no folders and therefore no library. Seed it with the folder on
 * the command line, or with the XDG music directory when there is one, so the
 * app opens onto something rather than onto an empty state the user has to
 * resolve before seeing anything work.
 */
private fun seedFirstRunFolder(store: DesktopLibrary, requested: String?) {
    if (store.folders().isNotEmpty() && requested == null) return
    val path = requested?.let { Path(it) } ?: Path(System.getProperty("user.home"), "Music")
    if (!path.exists() || !path.isDirectory()) return
    store.addFolder(path.toUri().toString(), path.toString())
}

/**
 * JAudiotagger logs an INFO line per file about ID3 padding and unknown iTunes
 * frames. On a first scan that is one line per track of noise about files that
 * are perfectly fine, so it is turned down to warnings.
 */
private fun quietenTagLibrary() {
    java.util.logging.Logger.getLogger("org.jaudiotagger").level = java.util.logging.Level.WARNING
}

/** Ask the OS for an unused port, then hand that number to Ktor. */
private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private fun openBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
            return
        }
    }
    // Desktop.browse is unreliable under some window managers; xdg-open is the
    // portable fallback and is present on every Debian desktop install.
    runCatching { ProcessBuilder("xdg-open", url).start() }
}

/** What the About screen says about *this* build (see AboutSpec). */
private fun desktopAbout(port: Int) = AboutSpec(
    versionName = APP_VERSION,
    tagline = "offline-first, pastel-themed music player for Linux.",
    overview = "Poet Music is an offline-first music player. The UI is an [htmx](https://htmx.org) " +
        "single-page app served by an embedded [Ktor](https://ktor.io) server running inside the app " +
        "process and rendered in a WebKitGTK view inside a native GTK window. Playback is handled by " +
        "GStreamer, using the codecs already installed on your system. It is the same application as " +
        "the Android build — the view layer, the routes and the frontend are one shared codebase.",
    techStack = listOf(
        "Language" to "Kotlin (JVM)",
        "UI" to "htmx single-page app in a WebKitGTK view, in a GTK 3 window",
        "Server" to "embedded Ktor (CIO) bound to `$LOOPBACK:$port`",
        "Playback" to "GStreamer `playbin`, with `equalizer-10bands` and `pitch`",
        "Media keys" to "MPRIS on the session bus — the panel applet and the keyboard media keys",
        "Tags" to "JAudiotagger — MP3, FLAC, OGG and M4A are all editable",
        "Storage" to "SQLite via JDBC, at `\$XDG_DATA_HOME/poet-music/library.db`",
        "Packaging" to "a `.deb` built with `jpackage`, carrying its own Java runtime"
    ),
    bindAddress = "$LOOPBACK:$port",
    architecture = listOf(
        "core/" to "the shared view layer, routes and platform interfaces — identical on Android and Linux.",
        "desktop/" to "the Linux implementations: JDBC store, GStreamer player, nio scanner, GTK window.",
        "app/src/main/assets/web/" to "the frontend, served byte-for-byte by both builds."
    ),
    storageNote = "Poet reads only the folders you add, and writes only to its own data directory."
)
