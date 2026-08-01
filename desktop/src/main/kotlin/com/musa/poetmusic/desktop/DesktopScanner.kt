package com.musa.poetmusic.desktop

import com.musa.poetmusic.data.LibraryStore
import com.musa.poetmusic.data.ScanPort
import com.musa.poetmusic.data.UNKNOWN_ALBUM
import com.musa.poetmusic.data.UNKNOWN_ARTIST
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The desktop library scanner: a `java.nio` walk with JAudiotagger reading the
 * tags (docs/desktop-app-plan.md §3.3).
 *
 * Same contract as the Android SAF scanner — same columns written, same
 * "Unknown artist"/"Unknown album" placeholders, same `.lrc` sidecar pairing by
 * base name, same removal of rows whose files have gone — so the shared
 * library screens and the Journal's tag-health numbers mean the same thing on
 * both platforms.
 */
class DesktopScanner(private val store: LibraryStore) : ScanPort {

    @Volatile override var isScanning: Boolean = false
        private set

    @Volatile override var progressText: String = ""
        private set

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "poet-scan").apply { isDaemon = true }
    }

    /** Fired after a scan finishes, so the caller can refresh anything cached. */
    @Volatile var onFinished: (() -> Unit)? = null

    override fun autoScanEnabled(): Boolean = store.getSetting(ScanPort.KEY_AUTO, "1") == "1"

    override fun setAutoScan(on: Boolean) {
        store.setSetting(ScanPort.KEY_AUTO, if (on) "1" else "0")
    }

    override fun intervalHours(): Int =
        store.getSetting(ScanPort.KEY_INTERVAL_H, ScanPort.DEFAULT_INTERVAL_H.toString())
            .toIntOrNull()?.takeIf { it in ScanPort.INTERVAL_CHOICES } ?: ScanPort.DEFAULT_INTERVAL_H

    override fun setIntervalHours(hours: Int) {
        store.setSetting(ScanPort.KEY_INTERVAL_H, hours.toString())
    }

    /**
     * Rescan on start when the library is stale, matching the phone's
     * "keep library in sync" behaviour. A `WatchService` folder watcher is
     * follow-up work (docs/desktop-app-plan.md §6); until then this and the
     * manual button are how the library keeps up.
     */
    fun maybeAutoScan() {
        if (!autoScanEnabled() || isScanning) return
        if (store.folders().isEmpty()) return
        val lastAt = store.getSetting(ScanPort.KEY_LAST_SCAN_AT, "0").toLongOrNull() ?: 0
        if (System.currentTimeMillis() - lastAt < intervalHours() * 3_600_000L) return
        startScan()
    }

    override fun startScan() {
        if (isScanning) return
        isScanning = true
        progressText = "Starting scan…"
        worker.execute {
            try {
                var found = 0
                store.folders().forEach { folder ->
                    val root = runCatching { Path.of(java.net.URI(folder.treeUri)) }.getOrNull()
                        ?: runCatching { Path.of(folder.displayPath) }.getOrNull()
                        ?: return@forEach
                    progressText = "Scanning ${folder.displayPath}…"
                    found += scanFolder(root, folder.id)
                }
                val stamp = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())
                store.setSetting("last_scan", "$found tracks · $stamp")
                store.setSetting(ScanPort.KEY_LAST_SCAN_AT, System.currentTimeMillis().toString())
            } catch (e: Exception) {
                store.setSetting("last_scan", "Scan failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                isScanning = false
                progressText = ""
                onFinished?.invoke()
            }
        }
    }

    /** Walks [root], upserting every audio file and dropping rows that vanished. */
    fun scanFolder(root: Path, folderId: Long): Int {
        val seen = mutableSetOf<String>()
        var found = 0
        // Collected first so the `.lrc` sidecars of a directory are known before
        // its audio files are written.
        val files = Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.map { it.toFile() }.toList()
        }
        val lrcByBase = files
            .filter { it.name.endsWith(".lrc", ignoreCase = true) }
            .associateBy({ it.parentFile.path + "/" + it.name.substringBeforeLast('.').lowercase() }, { it })

        files.filter { it.extension.lowercase() in ScanPort.AUDIO_EXTENSIONS }.forEach { file ->
            val uri = file.toURI().toString()
            seen += uri
            progressText = "Scanning ${file.name}"
            val lrc = lrcByBase[file.parentFile.path + "/" + file.nameWithoutExtension.lowercase()]
            upsert(file, folderId, lrc)
            found++
        }
        store.deleteMissingInFolder(folderId, seen)
        return found
    }

    private fun upsert(file: File, folderId: Long, lrc: File?) {
        var title = file.nameWithoutExtension
        var artist = UNKNOWN_ARTIST
        var album = UNKNOWN_ALBUM
        var durationMs = 0L
        var trackNo = 0
        var genre = ""
        var year = ""
        var albumArtist = ""
        var discNo = 0
        var composer = ""
        var hasArt = false

        // A file with a broken or absent tag still belongs in the library; the
        // filename stands in, exactly as the phone's scanner does.
        runCatching {
            val audio = AudioFileIO.read(file)
            durationMs = audio.audioHeader.trackLength * 1000L
            val tag = audio.tag
            if (tag != null) {
                tag.firstOrNull(FieldKey.TITLE)?.let { title = it }
                tag.firstOrNull(FieldKey.ARTIST)?.let { artist = it }
                tag.firstOrNull(FieldKey.ALBUM)?.let { album = it }
                genre = tag.firstOrNull(FieldKey.GENRE) ?: ""
                year = tag.firstOrNull(FieldKey.YEAR) ?: ""
                albumArtist = tag.firstOrNull(FieldKey.ALBUM_ARTIST) ?: ""
                composer = tag.firstOrNull(FieldKey.COMPOSER) ?: ""
                trackNo = tag.firstOrNull(FieldKey.TRACK)?.digits()?.toIntOrNull() ?: 0
                discNo = tag.firstOrNull(FieldKey.DISC_NO)?.digits()?.toIntOrNull() ?: 0
                hasArt = runCatching { tag.firstArtwork != null }.getOrDefault(false)
            }
        }

        store.upsertTrack(
            uri = file.toURI().toString(),
            parentUri = file.parentFile?.toURI()?.toString() ?: "",
            displayName = file.name,
            title = title.ifBlank { file.nameWithoutExtension },
            artist = artist.ifBlank { UNKNOWN_ARTIST },
            album = album.ifBlank { UNKNOWN_ALBUM },
            durationMs = durationMs,
            trackNo = trackNo,
            genre = genre,
            year = year,
            albumArtist = albumArtist,
            discNo = discNo,
            composer = composer,
            hasArt = hasArt,
            lrcUri = lrc?.toURI()?.toString(),
            folderId = folderId,
            lastModified = file.lastModified()
        )
    }
}

/** JAudiotagger returns "" for a missing field and throws on some containers. */
private fun org.jaudiotagger.tag.Tag.firstOrNull(key: FieldKey): String? =
    runCatching { getFirst(key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

/** Track numbers arrive as "3", "3/12" or "03"; keep the leading digits. */
private fun String.digits(): String = takeWhile { it.isDigit() }
