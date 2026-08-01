package com.musa.poetmusic.data

import java.io.InputStream

/**
 * Everything else the routes need from the platform
 * (docs/desktop-app-plan.md §2.1): bytes on disk, and the native affordances
 * that only some platforms have.
 *
 * The `*Requester` hooks are deliberately nullable. A null one means the
 * platform cannot do it, and the route answers with a toast — which is exactly
 * how the Android build already behaves before `MainActivity` has wired itself
 * up, so the desktop's permanent nulls (widget pinning, the system share
 * sheet, save-to-gallery) need no new code path.
 */
interface HostPort {

    /** A frontend asset by name, from `assets/web/` — the same files on both. */
    fun asset(name: String): ByteArray?

    /** Embedded cover art, or null when the file carries none. */
    fun embeddedArt(track: Track): ByteArray?

    /** The audio bytes, for `/api/stream/{id}`. */
    fun openAudioStream(track: Track): InputStream?

    /** Size in bytes for the song-info sheet, or -1 when unknown. */
    fun fileSize(track: Track): Long

    /** Physically delete the file. False when the platform refused. */
    fun deleteFile(track: Track): Boolean

    /** UTF-8 text at a track-relative uri — used for `.lrc` sidecars. */
    fun readText(uri: String): String?

    /**
     * The library or the playing track changed in a way something outside the
     * WebView shows. Android pushes the home screen widget; the desktop has
     * nothing to push yet (MPRIS is §6 follow-up work).
     */
    fun onLibraryChanged()

    // ---------- native affordances; null means "not on this platform" ----------

    val addFolderRequester: (() -> Unit)?
    val pinWidgetRequester: (() -> Unit)?
    val pickArtRequester: (() -> Unit)?
    val pickPortraitRequester: (() -> Unit)?
    val shareRequester: ((List<Long>) -> Unit)?
    val saveArtRequester: ((Long) -> Unit)?

    // ---------- listening journal portrait ----------

    fun portraitExists(): Boolean
    fun portraitBytes(): ByteArray?
    fun portraitMime(): String

    /** Stamp bumped whenever the portrait is replaced, so it can be cached hard. */
    fun portraitStamp(): String
}
