package com.musa.poetmusic.desktop

import com.musa.poetmusic.data.BatchTagForm
import com.musa.poetmusic.data.BatchTagResult
import com.musa.poetmusic.data.FileExtras
import com.musa.poetmusic.data.LibraryStore
import com.musa.poetmusic.data.TagForm
import com.musa.poetmusic.data.TagPort
import com.musa.poetmusic.data.TagResult
import com.musa.poetmusic.data.Track
import com.musa.poetmusic.data.UNKNOWN_ALBUM
import com.musa.poetmusic.data.UNKNOWN_ARTIST
import com.musa.poetmusic.data.fileNameFromPattern
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Desktop tag reading and writing through JAudiotagger
 * (docs/desktop-app-plan.md §3.3).
 *
 * A capability gain over the phone, whose hand-rolled ID3 writer only ever
 * handled MP3: FLAC, OGG, M4A and WMA become editable here too. The editor
 * asks [writesToFile] before it promises the user their edit reaches the file,
 * so a container JAudiotagger cannot write still saves to the Poet library and
 * says so.
 */
class DesktopTags(
    private val store: LibraryStore,
    private val onFileRenamed: (trackId: Long, newUri: String) -> Unit
) : TagPort {

    @Volatile override var pendingArt: ByteArray? = null
    @Volatile override var pendingArtMime: String = "image/jpeg"

    override fun clearPendingArt() {
        pendingArt = null
        pendingArtMime = "image/jpeg"
    }

    override fun writesToFile(track: Track): Boolean =
        track.displayName.substringAfterLast('.', "").lowercase() in WRITABLE

    override fun readFileExtras(track: Track): FileExtras = runCatching {
        val tag = AudioFileIO.read(track.file()).tag ?: return FileExtras(null, null)
        FileExtras(
            comment = runCatching { tag.getFirst(FieldKey.COMMENT) }.getOrNull()?.takeIf { it.isNotBlank() },
            lyrics = runCatching { tag.getFirst(FieldKey.LYRICS) }.getOrNull()?.takeIf { it.isNotBlank() }
        )
    }.getOrElse { FileExtras(null, null) }

    override fun saveTags(trackId: Long, form: TagForm): TagResult {
        val track = store.track(trackId) ?: return TagResult(false, "Track not found")
        val writable = writesToFile(track)

        val title = form.title.trim().ifBlank { track.title }
        val artist = form.artist.trim().ifBlank { UNKNOWN_ARTIST }
        val album = form.album.trim().ifBlank { UNKNOWN_ALBUM }
        val albumArtist = form.albumArtist.trim()
        val genre = form.genre.trim()
        val year = form.year.trim().filter(Char::isDigit).take(4)
        val trackNo = form.trackNo.trim().filter(Char::isDigit).take(4).toIntOrNull() ?: 0
        val discNo = form.discNo.trim().filter(Char::isDigit).take(3).toIntOrNull() ?: 0
        val composer = form.composer.trim()
        val comment = form.comment.trim()

        val customArt = if (form.artAction == "custom") pendingArt else null
        val removeArt = form.artAction == "remove"
        // Fire for all formats so the route evicts the cache and refreshes the UI,
        // even when the art is saved to a library-side override file rather than
        // embedded in the track.
        val artChanged = customArt != null || (removeArt && track.hasArt)

        var fileMessage =
            if (writable) "Tags written to file"
            else "Saved to library (this format can't be written here)"

        if (writable) {
            val written = runCatching {
                write(track.file()) { tag ->
                    tag.setFieldSafely(FieldKey.TITLE, title)
                    tag.setFieldSafely(FieldKey.ARTIST, artist)
                    tag.setFieldSafely(FieldKey.ALBUM, album)
                    if (albumArtist.isNotBlank()) tag.setFieldSafely(FieldKey.ALBUM_ARTIST, albumArtist)
                    if (genre.isNotBlank()) tag.setFieldSafely(FieldKey.GENRE, genre)
                    if (year.isNotBlank()) tag.setFieldSafely(FieldKey.YEAR, year)
                    if (trackNo > 0) tag.setFieldSafely(FieldKey.TRACK, trackNo.toString())
                    if (discNo > 0) tag.setFieldSafely(FieldKey.DISC_NO, discNo.toString())
                    if (composer.isNotBlank()) tag.setFieldSafely(FieldKey.COMPOSER, composer)
                    tag.setFieldSafely(FieldKey.COMMENT, comment)
                    if (form.lyrics.isNotBlank()) tag.setFieldSafely(FieldKey.LYRICS, form.lyrics)
                    when {
                        customArt != null -> {
                            runCatching { tag.deleteArtworkField() }
                            tag.setField(artworkOf(customArt))
                        }
                        removeArt -> runCatching { tag.deleteArtworkField() }
                    }
                }
            }
            if (written.isFailure) {
                fileMessage = "Saved to library — writing the file failed " +
                    "(${written.exceptionOrNull()?.message ?: "unknown error"})"
            }
        }

        // Non-writable: save artwork to a library-side override file so it
        // persists across rescans and survives the fact that the container
        // can't be written.
        if (!writable && artChanged) {
            val dir = artOverrideDir()
            dir.mkdirs()
            if (customArt != null) {
                File(dir, "${track.id}").writeBytes(customArt)
                File(dir, "${track.id}.mime").writeText(pendingArtMime)
                fileMessage += " \u00b7 artwork saved in Poet"
            } else if (removeArt) {
                File(dir, "${track.id}").delete()
                File(dir, "${track.id}.mime").delete()
                fileMessage += " \u00b7 artwork removed"
            }
        }

        store.updateTags(track.id, title, artist, album, genre, year, trackNo, albumArtist, discNo, composer, comment)
        if (artChanged) store.setHasArt(track.id, customArt != null)

        var renameNote = ""
        if (form.rename) {
            val newName = fileNameFromPattern(
                form.renamePattern, title, artist, album, trackNo,
                track.displayName.substringAfterLast('.', "")
            )
            renameNote = renameTo(track, newName)
        }

        clearPendingArt()
        return TagResult(true, fileMessage + renameNote, artChanged)
    }

    /** Moves the physical file and repoints the row and the play queue at it. */
    private fun renameTo(track: Track, newName: String): String {
        if (newName == track.displayName) return ""
        return runCatching {
            val source = track.file().toPath()
            val target = source.resolveSibling(newName)
            if (Files.exists(target)) return " · a file called “$newName” already exists"
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            store.updateTrackFile(track.id, target.toUri().toString(), newName)
            onFileRenamed(track.id, target.toUri().toString())
            " · renamed to $newName"
        }.getOrElse { " · rename failed" }
    }

    override fun applyBatch(ids: List<Long>, form: BatchTagForm): BatchTagResult {
        // Blank means "leave this field alone" — see BatchTagForm.
        val title = form.title.trim().takeIf { it.isNotEmpty() }
        val artist = form.artist.trim().takeIf { it.isNotEmpty() }
        val album = form.album.trim().takeIf { it.isNotEmpty() }
        val albumArtist = form.albumArtist.trim().takeIf { it.isNotEmpty() }
        val genre = form.genre.trim().takeIf { it.isNotEmpty() }
        val year = form.year.trim().filter(Char::isDigit).take(4).takeIf { it.isNotEmpty() }
        val trackNo = form.trackNo.trim().filter(Char::isDigit).take(4).toIntOrNull()?.takeIf { it > 0 }

        var written = 0
        var libraryOnly = 0
        var failed = 0
        ids.forEach { id ->
            val track = store.track(id)
            if (track == null) {
                failed++
                return@forEach
            }
            var fileOk = true
            if (writesToFile(track)) {
                fileOk = runCatching {
                    write(track.file()) { tag ->
                        title?.let { tag.setFieldSafely(FieldKey.TITLE, it) }
                        artist?.let { tag.setFieldSafely(FieldKey.ARTIST, it) }
                        album?.let { tag.setFieldSafely(FieldKey.ALBUM, it) }
                        albumArtist?.let { tag.setFieldSafely(FieldKey.ALBUM_ARTIST, it) }
                        genre?.let { tag.setFieldSafely(FieldKey.GENRE, it) }
                        year?.let { tag.setFieldSafely(FieldKey.YEAR, it) }
                        trackNo?.let { tag.setFieldSafely(FieldKey.TRACK, it.toString()) }
                    }
                }.isSuccess
                if (fileOk) written++ else failed++
            } else {
                libraryOnly++
            }
            store.updatePartialTags(id, title, artist, album, genre, year, trackNo, albumArtist)
        }
        return BatchTagResult(written, libraryOnly, failed)
    }

    override fun saveLrc(track: Track, lrcText: String): TagResult {
        if (lrcText.isBlank()) return TagResult(false, "No stamped lines to export yet")
        return runCatching {
            val target = Path.of(track.file().parent, track.displayName.substringBeforeLast('.') + ".lrc")
            Files.writeString(target, lrcText)
            store.updateLrcUri(track.id, target.toUri().toString())
            TagResult(true, "Synced lyrics saved to ${target.fileName}")
        }.getOrElse { TagResult(false, "Export failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    /** Read → mutate → commit, the shape every JAudiotagger write takes. */
    private fun write(file: File, block: (Tag) -> Unit) {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        block(tag)
        AudioFileIO.write(audio)
    }

    /** Directory for art override files (non-writable format edits). */
    private fun artOverrideDir(): File = File(DesktopLibrary.defaultDbFile().parentFile, "art-overrides")

    private fun artworkOf(bytes: ByteArray) = ArtworkFactory.getNew().apply {
        binaryData = bytes
        mimeType = pendingArtMime
        pictureType = 3 // front cover
    }

    /**
     * Some containers reject a field the format has no home for (a comment in a
     * bare M4A, say). One unsupported field must not abandon the whole write.
     */
    private fun Tag.setFieldSafely(key: FieldKey, value: String) {
        runCatching { setField(key, value) }
    }

    private companion object {
        /** Containers JAudiotagger can write, and that Poet accepts. */
        val WRITABLE = setOf("mp3", "flac", "ogg", "m4a", "mp4", "wma", "wav", "aiff", "opus")
    }
}

/** A track's uri is always a `file:` uri on the desktop. */
internal fun Track.file(): File = runCatching { File(java.net.URI(uri)) }.getOrElse { File(uri) }
