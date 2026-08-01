package com.musa.poetmusic.desktop.ui

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * The native application window: a GTK 3 window hosting a WebKitGTK view
 * (docs/desktop-app-plan.md §4).
 *
 * Poet's frontend is a browser app, so the desktop shell needs a browser
 * surface — the same role the Android build gives `WebView`. This binds the
 * system's GTK 3 and WebKitGTK through JNA rather than bundling a toolkit:
 *
 *  - JavaFX WebView would add ~90 MB of bundled WebKit to the .deb and ships a
 *    WebKit several years older than the one already installed here.
 *  - JCEF is larger still.
 *  - Both Debian and Ubuntu desktops carry libgtk-3 and libwebkit2gtk already,
 *    and the .deb declares them, so this costs the package nothing and gets a
 *    current WebKit (2.48 on the build machine) for free.
 *
 * GTK is not thread-safe and its main loop must own the process's first
 * thread, so [run] blocks the caller for the lifetime of the window; the Ktor
 * server is started before it on its own threads.
 *
 * If either library is missing (a headless box, a distro that only ships
 * webkit2gtk-4.0), [isAvailable] answers false and Main falls back to opening
 * the default browser rather than failing to start.
 */
object WebKitWindow {

    /** GTK's "delete-event"/"destroy" handlers and friends take no useful args. */
    fun interface GCallback : Callback {
        fun invoke(widget: Pointer?, data: Pointer?)
    }

    private interface Gtk : Library {
        fun gtk_init(argc: IntArray?, argv: Pointer?)
        fun gtk_window_new(type: Int): Pointer
        fun gtk_window_set_title(window: Pointer, title: String)
        fun gtk_window_set_default_size(window: Pointer, width: Int, height: Int)
        fun gtk_window_maximize(window: Pointer)
        fun gtk_container_add(container: Pointer, widget: Pointer)
        fun gtk_widget_show_all(widget: Pointer)
        fun gtk_widget_grab_focus(widget: Pointer)
        fun gtk_main()
        fun gtk_main_quit()
    }

    private interface Gobject : Library {
        fun g_signal_connect_data(
            instance: Pointer, detailedSignal: String, handler: Callback,
            data: Pointer?, destroyData: Pointer?, connectFlags: Int
        ): Long
    }

    private interface Glib : Library {
        fun g_idle_add(function: IdleCallback, data: Pointer?): Int
    }

    /** A GSourceFunc: returning false (0) removes the source after one run. */
    fun interface IdleCallback : Callback {
        fun invoke(data: Pointer?): Int
    }

    private interface WebKit : Library {
        fun webkit_web_view_new(): Pointer
        fun webkit_web_view_load_uri(webView: Pointer, uri: String)
        fun webkit_web_view_get_settings(webView: Pointer): Pointer
        fun webkit_settings_set_enable_developer_extras(settings: Pointer, enabled: Boolean)
        fun webkit_settings_set_enable_page_cache(settings: Pointer, enabled: Boolean)
        fun webkit_settings_set_media_playback_requires_user_gesture(settings: Pointer, enabled: Boolean)
        fun webkit_settings_set_enable_smooth_scrolling(settings: Pointer, enabled: Boolean)
        fun webkit_web_context_get_default(): Pointer
        fun webkit_web_context_set_cache_model(context: Pointer, model: Int)
    }

    private const val GTK_WINDOW_TOPLEVEL = 0

    /** WEBKIT_CACHE_MODEL_DOCUMENT_BROWSER — keep assets warm across swaps. */
    private const val CACHE_MODEL_DOCUMENT_BROWSER = 2

    /**
     * Loaded lazily and never rethrown: a missing library is a supported
     * outcome (see the class comment), not a crash.
     */
    private class Libs(val gtk: Gtk, val gobject: Gobject, val glib: Glib, val webkit: WebKit)

    private val libs: Libs? by lazy {
        runCatching {
            val gtk = Native.load("gtk-3", Gtk::class.java)
            val gobject = Native.load("gobject-2.0", Gobject::class.java)
            val glib = Native.load("glib-2.0", Glib::class.java)
            // 4.1 is what Debian 12+ and Ubuntu 22.04+ ship; 4.0 covers the
            // older stable releases still in the wild.
            val webkit = runCatching { Native.load("webkit2gtk-4.1", WebKit::class.java) }
                .getOrElse { Native.load("webkit2gtk-4.0", WebKit::class.java) }
            Libs(gtk, gobject, glib, webkit)
        }.getOrNull()
    }

    /** The live GTK window, for dialogs that want a parent. Null until [run]. */
    @Volatile
    var windowHandle: Pointer? = null
        private set

    val isAvailable: Boolean get() = libs != null

    /**
     * References the GTK callbacks so the JVM cannot collect them while the
     * native side still holds their addresses — the classic JNA foot-gun.
     */
    private val callbackRefs = mutableListOf<Callback>()

    /**
     * Open the window on [url] and run the GTK main loop until it is closed.
     * Blocks. Returns false without doing anything when GTK is unavailable.
     */
    fun run(url: String, title: String, onClosed: () -> Unit = {}): Boolean {
        val lib = libs ?: return false
        val gtk = lib.gtk
        val gobject = lib.gobject
        val webkit = lib.webkit

        gtk.gtk_init(null, null)

        val window = gtk.gtk_window_new(GTK_WINDOW_TOPLEVEL)
        gtk.gtk_window_set_title(window, title)
        // Opens straight into the PC-compact tier (poet.css >= 1100px), so the
        // first frame is the desktop layout rather than the phone column.
        gtk.gtk_window_set_default_size(window, 1280, 820)

        val view = webkit.webkit_web_view_new()
        val settings = webkit.webkit_web_view_get_settings(view)
        webkit.webkit_settings_set_enable_developer_extras(settings, true)
        webkit.webkit_settings_set_enable_page_cache(settings, true)
        // The frontend starts playback from JS on user actions that WebKit does
        // not always classify as gestures; the audio is ours, not a web page's.
        webkit.webkit_settings_set_media_playback_requires_user_gesture(settings, false)
        webkit.webkit_settings_set_enable_smooth_scrolling(settings, true)
        webkit.webkit_web_context_set_cache_model(
            webkit.webkit_web_context_get_default(), CACHE_MODEL_DOCUMENT_BROWSER
        )

        gtk.gtk_container_add(window, view)
        webkit.webkit_web_view_load_uri(view, url)

        val onDestroy = GCallback { _, _ ->
            onClosed()
            gtk.gtk_main_quit()
        }
        callbackRefs += onDestroy
        gobject.g_signal_connect_data(window, "destroy", onDestroy, null, null, 0)

        windowHandle = window
        gtk.gtk_widget_show_all(window)
        gtk.gtk_widget_grab_focus(view)
        gtk.gtk_main()
        windowHandle = null
        return true
    }

    /**
     * Run [block] on the GTK main loop, once. GTK widgets may only be touched
     * from that thread, so anything a Ktor worker wants to show — the file
     * chooser, for one — has to come through here.
     *
     * Returns false when GTK is unavailable, so callers can fall back rather
     * than wait on a latch nothing will ever count down.
     */
    fun postToMainLoop(block: () -> Unit): Boolean {
        val lib = libs ?: return false
        lateinit var callback: IdleCallback
        callback = IdleCallback {
            try {
                block()
            } finally {
                // The source is one-shot, so the reference can go now.
                synchronized(callbackRefs) { callbackRefs.remove(callback) }
            }
            0
        }
        synchronized(callbackRefs) { callbackRefs += callback }
        lib.glib.g_idle_add(callback, null)
        return true
    }
}
