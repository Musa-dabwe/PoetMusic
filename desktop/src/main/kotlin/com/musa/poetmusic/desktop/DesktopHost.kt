package com.musa.poetmusic.desktop

import com.musa.poetmusic.data.HostPort
import com.musa.poetmusic.data.LibraryStore
import com.musa.poetmusic.data.Track
import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.Files

/**
 * The desktop side of [HostPort] (docs/desktop-app-plan.md §3.4): bytes on
 * disk, and the native affordances a Linux desktop actually has.
 *
 * Three of the Android hooks have no desktop counterpart — pinning a home
 * screen widget, the system share sheet, and saving a cover to a photo gallery.
 * They stay null, which the shared routes already treat as "answer with a
 * toast", so there is no desktop-only branch anywhere above this file.
 */
class DesktopHost(
    private val store: LibraryStore,
    private val tags: DesktopTags,
    private val onFolderAdded: (File) -> Unit
) : HostPort {

    /**
     * The GTK folder chooser. The dialog runs on the GTK main loop and blocks
     * this callback's thread, which is the requester thread the route already
     * released — the route answered 204 the moment it fired this.
     */
    override val addFolderRequester: (() -> Unit)? = {
        com.musa.poetmusic.desktop.ui.FileChooser.chooseFolder()?.let { dir ->
            store.addFolder(dir.toURI().toString(), dir.absolutePath)
            onFolderAdded(dir)
        }
    }

    override val pickArtRequester: (() -> Unit)? = {
        com.musa.poetmusic.desktop.ui.FileChooser.chooseImage("Choose cover art")?.let { image ->
            runCatching {
                tags.pendingArt = image.readBytes()
                tags.pendingArtMime = Files.probeContentType(image.toPath()) ?: "image/jpeg"
            }
        }
    }

    override val pickPortraitRequester: (() -> Unit)? = {
        com.musa.poetmusic.desktop.ui.FileChooser.chooseImage("Choose a journal portrait")?.let { image ->
            runCatching {
                portraitFile().parentFile?.mkdirs()
                image.copyTo(portraitFile(), overwrite = true)
                store.setSetting(PORTRAIT_MIME_KEY, Files.probeContentType(image.toPath()) ?: "image/jpeg")
                store.setSetting(PORTRAIT_STAMP_KEY, System.currentTimeMillis().toString())
            }
        }
    }

    // No desktop counterpart — see the class comment.
    override val pinWidgetRequester: (() -> Unit)? = null
    override val shareRequester: ((List<Long>) -> Unit)? = null
    override val saveArtRequester: ((Long) -> Unit)? = null

    /**
     * The frontend assets come out of the jar, where `:desktop` puts
     * `app/src/main/assets` on its resource path — the phone and the desktop
     * serve the very same `poet.css` and `poet.js`, so they cannot drift.
     */
    override fun asset(name: String): ByteArray? =
        DesktopHost::class.java.classLoader.getResourceAsStream("web/$name")?.use { it.readBytes() }

    override fun embeddedArt(track: Track): ByteArray? = runCatching {
        AudioFileIO.read(track.file()).tag?.firstArtwork?.binaryData
    }.getOrNull()

    override fun openAudioStream(track: Track): InputStream? =
        runCatching { track.file().inputStream() }.getOrNull()

    override fun fileSize(track: Track): Long =
        runCatching { track.file().length() }.getOrDefault(-1L)

    override fun deleteFile(track: Track): Boolean =
        runCatching { Files.deleteIfExists(track.file().toPath()) }.getOrDefault(false)

    override fun readText(uri: String): String? = runCatching {
        File(URI(uri)).readText()
    }.getOrNull()

    /**
     * Told when something outside the window needs re-reading — which on Linux
     * means the MPRIS metadata a desktop applet is showing, so a tag edit
     * reaches the panel without waiting for the next song. Set by `main` once
     * the session bus is up; left null when there is none.
     */
    var onLibraryChangedHook: (() -> Unit)? = null

    override fun onLibraryChanged() {
        onLibraryChangedHook?.invoke()
    }

    // ---------- listening journal portrait ----------

    private fun portraitFile(): File = File(DesktopLibrary.defaultDbFile().parentFile, "journal-portrait")

    override fun portraitExists(): Boolean = portraitFile().isFile
    override fun portraitBytes(): ByteArray? = portraitFile().takeIf { it.isFile }?.readBytes()
    override fun portraitMime(): String = store.getSetting(PORTRAIT_MIME_KEY, "image/jpeg")
    override fun portraitStamp(): String = store.getSetting(PORTRAIT_STAMP_KEY, "0")

    private companion object {
        const val PORTRAIT_MIME_KEY = "journal_portrait_mime"
        const val PORTRAIT_STAMP_KEY = "journal_portrait_at"
    }
}
