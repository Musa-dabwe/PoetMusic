package com.musa.poetmusic.desktop.ui

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The folder and image pickers, using GTK's own file chooser
 * (docs/desktop-app-plan.md §3.4).
 *
 * The Android build hands these to SAF and the gallery picker; here they are
 * `GtkFileChooserDialog` through the same JNA binding the window already uses,
 * so the app pulls in no second toolkit and the dialogs look like every other
 * dialog on the desktop.
 *
 * GTK is not thread-safe, so the dialog is opened from the GTK main loop
 * ([WebKitWindow.postToMainLoop]) and the calling Ktor thread waits for the
 * answer — the same shape as the phone, where the picker result arrives back
 * on the main thread.
 */
object FileChooser {

    private interface Gtk : Library {
        fun gtk_file_chooser_dialog_new(
            title: String, parent: Pointer?, action: Int,
            firstButtonText: String?, firstResponse: Int,
            secondButtonText: String?, secondResponse: Int,
            terminator: Pointer?
        ): Pointer

        fun gtk_dialog_run(dialog: Pointer): Int
        fun gtk_file_chooser_get_filename(chooser: Pointer): Pointer?
        fun gtk_widget_destroy(widget: Pointer)
        fun gtk_file_chooser_set_current_folder(chooser: Pointer, path: String): Boolean
        fun gtk_file_filter_new(): Pointer
        fun gtk_file_filter_set_name(filter: Pointer, name: String)
        fun gtk_file_filter_add_mime_type(filter: Pointer, mime: String)
        fun gtk_file_chooser_add_filter(chooser: Pointer, filter: Pointer)
    }

    private const val ACTION_OPEN = 0
    private const val ACTION_SELECT_FOLDER = 2
    private const val RESPONSE_CANCEL = -6
    private const val RESPONSE_ACCEPT = -3

    private val gtk: Gtk? by lazy { runCatching { Native.load("gtk-3", Gtk::class.java) }.getOrNull() }

    /** Ask for a music folder. Blocks the caller until the dialog closes. */
    fun chooseFolder(title: String = "Add a music folder"): File? =
        choose(title, ACTION_SELECT_FOLDER, imagesOnly = false)

    /** Ask for an image (cover art, journal portrait). */
    fun chooseImage(title: String = "Choose an image"): File? =
        choose(title, ACTION_OPEN, imagesOnly = true)

    private fun choose(title: String, action: Int, imagesOnly: Boolean): File? {
        val lib = gtk ?: return null
        if (!WebKitWindow.isAvailable) return null

        var picked: File? = null
        val done = CountDownLatch(1)
        val posted = WebKitWindow.postToMainLoop {
            try {
                val dialog = lib.gtk_file_chooser_dialog_new(
                    title, WebKitWindow.windowHandle, action,
                    "_Cancel", RESPONSE_CANCEL, "_Select", RESPONSE_ACCEPT, null
                )
                if (imagesOnly) {
                    val filter = lib.gtk_file_filter_new()
                    lib.gtk_file_filter_set_name(filter, "Images")
                    listOf("image/jpeg", "image/png", "image/webp").forEach {
                        lib.gtk_file_filter_add_mime_type(filter, it)
                    }
                    lib.gtk_file_chooser_add_filter(dialog, filter)
                }
                val home = System.getProperty("user.home")
                val start = if (action == ACTION_SELECT_FOLDER) File(home, "Music").takeIf { it.isDirectory } else null
                lib.gtk_file_chooser_set_current_folder(dialog, (start ?: File(home)).absolutePath)

                if (lib.gtk_dialog_run(dialog) == RESPONSE_ACCEPT) {
                    picked = lib.gtk_file_chooser_get_filename(dialog)?.getString(0)?.let(::File)
                }
                lib.gtk_widget_destroy(dialog)
            } finally {
                done.countDown()
            }
        }
        if (!posted) return null
        // Generous: the user is looking at a dialog, and there is no deadline
        // worth enforcing on a human. The Ktor request that opened it has
        // already answered — this runs on the requester callback's own thread.
        done.await(10, TimeUnit.MINUTES)
        return picked
    }
}
