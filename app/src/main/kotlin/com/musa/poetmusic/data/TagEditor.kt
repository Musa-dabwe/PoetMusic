package com.musa.poetmusic.data

import android.content.Context
import android.net.Uri
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import java.io.File

/**
 * ID3 tag editing. MP3 files get their ID3v2 frames rewritten in place
 * (via a cache-file round trip, since SAF only exposes streams); every
 * other format gets a library-side metadata update.
 */
object TagEditor {

    data class Result(val ok: Boolean, val message: String)

    fun saveTags(
        context: Context, db: MusicDatabase, trackId: Long,
        title: String, artist: String, album: String,
        genre: String, year: String, trackNo: String
    ): Result {
        val track = db.track(trackId) ?: return Result(false, "Track not found")
        val cleanTitle = title.trim().ifBlank { track.title }
        val cleanArtist = artist.trim().ifBlank { "Unknown artist" }
        val cleanAlbum = album.trim().ifBlank { "Unknown album" }
        val cleanGenre = genre.trim()
        val cleanYear = year.trim().filter(Char::isDigit).take(4)
        val cleanTrackNo = trackNo.trim().filter(Char::isDigit).take(4).toIntOrNull() ?: 0

        var fileMessage = "Saved to library"
        if (track.displayName.endsWith(".mp3", ignoreCase = true)) {
            fileMessage = try {
                writeMp3Tags(context, Uri.parse(track.uri), cleanTitle, cleanArtist, cleanAlbum, cleanGenre, cleanYear, cleanTrackNo)
                "Tags written to file and library"
            } catch (e: Exception) {
                "Saved to library only (file write failed: ${e.message ?: e.javaClass.simpleName})"
            }
        } else {
            fileMessage = "Saved to library (ID3 file writing supports MP3 only)"
        }

        db.updateTags(trackId, cleanTitle, cleanArtist, cleanAlbum, cleanGenre, cleanYear, cleanTrackNo)
        return Result(true, fileMessage)
    }

    private fun writeMp3Tags(
        context: Context, uri: Uri, title: String, artist: String, album: String,
        genre: String, year: String, trackNo: Int
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
            if (genre.isNotBlank()) runCatching { tag.genreDescription = genre }
            if (year.isNotBlank()) tag.year = year
            if (trackNo > 0) tag.track = trackNo.toString()
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
