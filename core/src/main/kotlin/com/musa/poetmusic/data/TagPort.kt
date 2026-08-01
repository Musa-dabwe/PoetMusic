package com.musa.poetmusic.data

data class TagResult(val ok: Boolean, val message: String, val artChanged: Boolean = false)

/** The single-track editor form, straight off the sheet. */
data class TagForm(
    val title: String, val artist: String, val album: String, val albumArtist: String,
    val genre: String, val year: String, val trackNo: String, val discNo: String,
    val composer: String, val comment: String, val lyrics: String,
    /** "keep" | "custom" | "remove" */
    val artAction: String,
    val rename: Boolean, val renamePattern: String
)

/**
 * The multi-select form. Every field starts blank, and blank means "keep what
 * each track already has" — so a batch can only ever add information.
 */
data class BatchTagForm(
    val title: String = "", val artist: String = "", val album: String = "",
    val albumArtist: String = "", val genre: String = "", val year: String = "",
    val trackNo: String = ""
) {
    fun isEmpty(): Boolean = listOf(title, artist, album, albumArtist, genre, year, trackNo)
        .all { it.isBlank() }
}

data class BatchTagResult(val written: Int, val libraryOnly: Int, val failed: Int) {
    val touched: Int get() = written + libraryOnly

    val message: String
        get() = when {
            touched == 0 -> "Couldn't update any of the selected songs"
            failed > 0 -> "Updated $touched, $failed failed"
            libraryOnly == 0 -> "Tags written to $written ${if (written == 1) "file" else "files"}"
            written == 0 -> "Saved to library for $libraryOnly ${if (libraryOnly == 1) "song" else "songs"}"
            else -> "$written written to file · $libraryOnly saved to library"
        }
}

/** Fields that only exist in the file, read for the editor's prefill. */
data class FileExtras(val comment: String?, val lyrics: String?)

/**
 * Tag reading and writing, as the tag editor sees it
 * (docs/desktop-app-plan.md §2.1).
 *
 * Android writes ID3v2 through mp3agic and is MP3-only; the desktop writes
 * through JAudiotagger and also covers FLAC, OGG and M4A. [writesToFile] is
 * what the editor asks before promising the user their edit reaches the file —
 * when it is false the tags are saved to the Poet library only.
 */
interface TagPort {

    /** Cover art the user picked but has not saved yet. */
    var pendingArt: ByteArray?
    var pendingArtMime: String

    fun clearPendingArt()

    /** True when this platform can write tags into [track]'s container. */
    fun writesToFile(track: Track): Boolean

    fun readFileExtras(track: Track): FileExtras

    fun saveTags(trackId: Long, form: TagForm): TagResult

    fun applyBatch(ids: List<Long>, form: BatchTagForm): BatchTagResult

    /** Export the synced-lyrics LRC built in the editor to a sidecar file. */
    fun saveLrc(track: Track, lrcText: String): TagResult
}

/**
 * Fills the file name pattern with the same fallbacks the sheet's live preview
 * uses, strips characters file systems and SAF providers reject, and makes
 * sure the original audio extension survives.
 *
 * Pure, and shared: both platforms offer the same placeholders and the same
 * scrub, they just move the file differently afterwards.
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
