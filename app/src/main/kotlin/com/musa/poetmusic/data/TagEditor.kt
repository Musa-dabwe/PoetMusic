package com.musa.poetmusic.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import com.musa.poetmusic.playback.PlayerController
import java.io.File

/**
 * Local-file metadata editing. MP3 files get their ID3v2 frames rewritten in
 * place (via a cache-file round trip, since SAF only exposes streams); every
 * other format gets a library-side metadata update. Also owns the optional
 * physical file rename and sidecar .lrc export used by the tag editor sheet.
 */
object TagEditor {

    data class Result(val ok: Boolean, val message: String, val artChanged: Boolean = false)

    /** Everything the editor sheet submits in one save. */
    data class Form(
        val title: String, val artist: String, val album: String, val albumArtist: String,
        val genre: String, val year: String, val trackNo: String, val discNo: String,
        val composer: String, val comment: String, val lyrics: String,
        val artAction: String,      // "keep" | "custom" | "remove"
        val rename: Boolean, val renamePattern: String
    )

    /** Tag fields only readable from the file itself, not cached in the library. */
    data class FileExtras(val comment: String?, val lyrics: String?)

    /** Cover image picked in the native gallery, parked here until save. */
    @Volatile var pendingArt: ByteArray? = null
    @Volatile var pendingArtMime: String = "image/jpeg"

    fun clearPendingArt() {
        pendingArt = null
        pendingArtMime = "image/jpeg"
    }

    fun saveTags(context: Context, db: MusicDatabase, trackId: Long, form: Form): Result {
        val track = db.track(trackId) ?: return Result(false, "Track not found")
        val isMp3 = track.displayName.endsWith(".mp3", ignoreCase = true)

        val cleanTitle = form.title.trim().ifBlank { track.title }
        val cleanArtist = form.artist.trim().ifBlank { MusicDatabase.UNKNOWN_ARTIST }
        val cleanAlbum = form.album.trim().ifBlank { MusicDatabase.UNKNOWN_ALBUM }
        val cleanAlbumArtist = form.albumArtist.trim()
        val cleanGenre = form.genre.trim()
        val cleanYear = form.year.trim().filter(Char::isDigit).take(4)
        val cleanTrackNo = form.trackNo.trim().filter(Char::isDigit).take(4).toIntOrNull() ?: 0
        val cleanDiscNo = form.discNo.trim().filter(Char::isDigit).take(3).toIntOrNull() ?: 0
        val cleanComposer = form.composer.trim()
        val cleanComment = form.comment.trim()

        val customArt = if (form.artAction == "custom") pendingArt else null
        val removeArt = form.artAction == "remove"
        val artChanged = isMp3 && (customArt != null || (removeArt && track.hasArt))

        var fileMessage = if (isMp3) "Tags written to file" else "Saved to library (file tag writing supports MP3 only)"
        if (isMp3) {
            try {
                writeMp3Tags(
                    context, Uri.parse(track.uri),
                    cleanTitle, cleanArtist, cleanAlbum, cleanAlbumArtist, cleanGenre, cleanYear,
                    cleanTrackNo, cleanDiscNo, cleanComposer, cleanComment, form.lyrics,
                    customArt, if (customArt != null) pendingArtMime else null, removeArt
                )
            } catch (e: Exception) {
                return Result(
                    true,
                    "Saved to library only (file write failed: ${e.message ?: e.javaClass.simpleName})"
                ).also {
                    db.updateTags(
                        trackId, cleanTitle, cleanArtist, cleanAlbum, cleanGenre, cleanYear,
                        cleanTrackNo, cleanAlbumArtist, cleanDiscNo, cleanComposer, cleanComment
                    )
                }
            }
        }

        db.updateTags(
            trackId, cleanTitle, cleanArtist, cleanAlbum, cleanGenre, cleanYear,
            cleanTrackNo, cleanAlbumArtist, cleanDiscNo, cleanComposer, cleanComment
        )
        if (artChanged) db.setHasArt(trackId, customArt != null)
        if (customArt != null) clearPendingArt()

        if (form.rename) {
            val newName = fileNameFromPattern(
                form.renamePattern, cleanTitle, cleanArtist, cleanAlbum, cleanTrackNo,
                track.displayName.substringAfterLast('.', "")
            )
            if (newName != track.displayName) {
                fileMessage += try {
                    val newUri = DocumentsContract.renameDocument(
                        context.contentResolver, Uri.parse(track.uri), newName
                    )
                    if (newUri != null) {
                        db.updateTrackFile(trackId, newUri.toString(), newName)
                        PlayerController.onTrackFileRenamed(trackId, newUri.toString())
                        " · renamed to $newName"
                    } else " · rename failed"
                } catch (e: Exception) {
                    " · rename failed (${e.message ?: e.javaClass.simpleName})"
                }
            }
        }

        return Result(true, fileMessage, artChanged)
    }

    /**
     * Fills the file name pattern with the same fallbacks the sheet's live
     * preview uses, strips characters SAF providers reject, and makes sure
     * the original audio extension survives.
     */
    fun fileNameFromPattern(
        pattern: String, title: String, artist: String, album: String, trackNo: Int, ext: String
    ): String {
        val dotExt = if (ext.isBlank()) "" else ".$ext"
        var base = pattern.ifBlank { "%track% - %title%" }
            .replace("%track%", if (trackNo > 0) trackNo.toString() else "00")
            .replace("%title%", title.ifBlank { "Untitled" })
            .replace("%artist%", artist.ifBlank { "Unknown" })
            .replace("%album%", album.ifBlank { "Album" })
        // The pattern may already carry the extension; strip it so we don't double it.
        if (dotExt.isNotEmpty() && base.endsWith(dotExt, ignoreCase = true)) {
            base = base.dropLast(dotExt.length)
        }
        base = base.replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ").trim().removeSuffix(".").trim()
        if (base.length > 120) base = base.take(120).trim()
        return (base.ifBlank { "Untitled" }) + dotExt
    }

    /** Reads COMM + USLT from an MP3 so the editor can prefill them. */
    fun readFileExtras(context: Context, track: Track): FileExtras {
        if (!track.displayName.endsWith(".mp3", ignoreCase = true)) return FileExtras(null, null)
        val cacheDir = File(context.cacheDir, "tagedit").apply { mkdirs() }
        val src = File(cacheDir, "read-src.mp3")
        return try {
            context.contentResolver.openInputStream(Uri.parse(track.uri)).use { input ->
                requireNotNull(input) { "Cannot open file" }
                src.outputStream().use { input.copyTo(it) }
            }
            val mp3 = Mp3File(src.absolutePath)
            val tag = if (mp3.hasId3v2Tag()) mp3.id3v2Tag else null
            FileExtras(tag?.comment, tag?.lyrics)
        } catch (_: Exception) {
            FileExtras(null, null)
        } finally {
            src.delete()
        }
    }

    /**
     * Writes the synced lyrics produced by the LRC maker to a sidecar .lrc
     * file next to the track (overwriting the already-linked file if there is
     * one) and links it in the library.
     */
    fun saveLrc(context: Context, db: MusicDatabase, track: Track, lrcText: String): Result {
        if (lrcText.isBlank()) return Result(false, "No stamped lines to export yet")
        val resolver = context.contentResolver
        try {
            val existing = track.lrcUri
            if (existing != null) {
                resolver.openOutputStream(Uri.parse(existing), "wt").use { out ->
                    requireNotNull(out) { "Cannot write .lrc" }
                    out.write(lrcText.toByteArray(Charsets.UTF_8))
                }
                return Result(true, "Synced lyrics saved to ${lrcName(track)}")
            }
            if (track.parentUri.isBlank()) return Result(false, "Track folder unknown — rescan the library first")
            val created = DocumentsContract.createDocument(
                resolver, Uri.parse(track.parentUri), "application/octet-stream", lrcName(track)
            ) ?: return Result(false, "Could not create the .lrc file")
            resolver.openOutputStream(created, "wt").use { out ->
                requireNotNull(out) { "Cannot write .lrc" }
                out.write(lrcText.toByteArray(Charsets.UTF_8))
            }
            db.updateLrcUri(track.id, created.toString())
            return Result(true, "Synced lyrics saved to ${lrcName(track)}")
        } catch (e: Exception) {
            return Result(false, "Export failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun lrcName(track: Track): String =
        track.displayName.substringBeforeLast('.') + ".lrc"

    private fun writeMp3Tags(
        context: Context, uri: Uri,
        title: String, artist: String, album: String, albumArtist: String, genre: String,
        year: String, trackNo: Int, discNo: Int, composer: String, comment: String,
        lyrics: String, artBytes: ByteArray?, artMime: String?, removeArt: Boolean
    ) {
        val cacheDir = File(context.cacheDir, "tagedit").apply { mkdirs() }
        val src = File(cacheDir, "edit-src.mp3")
        val dst = File(cacheDir, "edit-dst.mp3")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open file" }
                src.outputStream().use { input.copyTo(it) }
            }
            val mp3 = Mp3File(src.absolutePath)
            val tag = if (mp3.hasId3v2Tag()) mp3.id3v2Tag else ID3v24Tag().also { mp3.id3v2Tag = it }
            tag.title = title
            tag.artist = artist
            tag.album = album
            runCatching { tag.albumArtist = albumArtist }
            if (genre.isNotBlank()) runCatching { tag.genreDescription = genre }
            if (year.isNotBlank()) tag.year = year
            if (trackNo > 0) tag.track = trackNo.toString()
            if (discNo > 0) runCatching { tag.partOfSet = discNo.toString() }
            runCatching { tag.composer = composer }
            runCatching { tag.comment = comment }
            runCatching { tag.lyrics = lyrics }
            if (artBytes != null) {
                runCatching { tag.clearAlbumImage() }
                tag.setAlbumImage(artBytes, artMime ?: "image/jpeg")
            } else if (removeArt) {
                runCatching { tag.clearAlbumImage() }
            }
            if (mp3.hasId3v1Tag()) {
                mp3.id3v1Tag.title = title
                mp3.id3v1Tag.artist = artist
                mp3.id3v1Tag.album = album
                if (year.isNotBlank()) mp3.id3v1Tag.year = year
                if (trackNo > 0) runCatching { mp3.id3v1Tag.track = trackNo.toString() }
            }
            mp3.save(dst.absolutePath)
            context.contentResolver.openOutputStream(uri, "wt").use { output ->
                requireNotNull(output) { "Cannot write file" }
                dst.inputStream().use { it.copyTo(output) }
            }
        } finally {
            src.delete()
            dst.delete()
        }
    }
}
