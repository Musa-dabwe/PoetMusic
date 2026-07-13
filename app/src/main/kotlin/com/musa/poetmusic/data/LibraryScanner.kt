package com.musa.poetmusic.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recursively walks the SAF document trees the user mapped in Settings,
 * extracts audio metadata and caches it in the local SQLite library.
 */
object LibraryScanner {

    private val audioExtensions = setOf("mp3", "flac", "m4a", "wav", "ogg", "opus", "aac", "mid", "wma")
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    @Volatile var isScanning: Boolean = false; private set
    @Volatile var progressText: String = ""; private set

    @Volatile var onFinished: (() -> Unit)? = null

    fun startScan(context: Context, db: MusicDatabase) {
        if (!running.compareAndSet(false, true)) return
        isScanning = true
        progressText = "Reading mapped folders…"
        val app = context.applicationContext
        executor.execute {
            var found = 0
            try {
                for (folder in db.folders()) {
                    val treeUri = Uri.parse(folder.treeUri)
                    val seen = mutableSetOf<String>()
                    try {
                        val rootDoc = DocumentsContract.getTreeDocumentId(treeUri)
                        found += scanDirectory(app, db, treeUri, rootDoc, folder.id, seen)
                    } catch (_: Exception) {
                        // Folder permission may have been revoked; skip it.
                    }
                    db.deleteMissingInFolder(folder.id, seen)
                }
                val stamp = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())
                db.setSetting("last_scan", "Last scanned $stamp — ${db.trackCount()} tracks")
            } finally {
                isScanning = false
                running.set(false)
                onFinished?.let { runCatching(it) }
            }
        }
    }

    private fun scanDirectory(
        context: Context, db: MusicDatabase, treeUri: Uri, dirDocId: String,
        folderId: Long, seen: MutableSet<String>
    ): Int {
        data class Entry(val docId: String, val name: String, val mime: String)

        val children = mutableListOf<Entry>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                children += Entry(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "")
            }
        }

        // Map of base filename -> .lrc document uri, used to attach timed lyrics.
        val lrcByBase = children
            .filter { it.name.endsWith(".lrc", ignoreCase = true) }
            .associateBy(
                { it.name.substringBeforeLast('.').lowercase() },
                { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.docId).toString() }
            )

        var found = 0
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirDocId).toString()
        for (child in children) {
            if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                found += scanDirectory(context, db, treeUri, child.docId, folderId, seen)
                continue
            }
            val ext = child.name.substringAfterLast('.', "").lowercase()
            val isAudio = child.mime.startsWith("audio/") || ext in audioExtensions
            if (!isAudio) continue

            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId)
            progressText = "Scanning: ${child.name}"
            val base = child.name.substringBeforeLast('.')
            var title = base
            var artist = "Unknown artist"
            var album = "Unknown album"
            var durationMs = 0L
            var trackNo = 0
            var hasArt = false
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, docUri)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { durationMs = it }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.trim()?.toIntOrNull()?.let { trackNo = it }
                hasArt = mmr.embeddedPicture != null
            } catch (_: Exception) {
                // Unreadable file; index it by name only.
            } finally {
                runCatching { mmr.release() }
            }

            db.upsertTrack(
                uri = docUri.toString(), parentUri = parentUri, displayName = child.name,
                title = title, artist = artist, album = album, durationMs = durationMs,
                trackNo = trackNo, hasArt = hasArt, lrcUri = lrcByBase[base.lowercase()], folderId = folderId
            )
            seen += docUri.toString()
            found++
        }
        return found
    }
}
