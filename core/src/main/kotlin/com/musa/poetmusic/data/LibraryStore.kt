package com.musa.poetmusic.data

/** Placeholders the scanners write when a file carries no artist/album tag. */
const val UNKNOWN_ARTIST = "Unknown artist"
const val UNKNOWN_ALBUM = "Unknown album"

/**
 * Tag fields the journal's integrity index scores per track: artist, album,
 * genre, year, track number and album artist.
 */
const val CORE_TAG_FIELDS = 6

/** How deep each journal leaderboard is read, and shown. */
const val JOURNAL_TOP_N = 10

/**
 * The library, as the view layer and the routes see it
 * (docs/desktop-app-plan.md §2.1).
 *
 * Android backs this with `android.database.sqlite` and the desktop with
 * `sqlite-jdbc`, over the same schema and the same SQL. Everything above this
 * line — every `Views*.kt` file, every route — names this interface and not
 * either implementation, which is what lets the whole frontend be shared
 * instead of duplicated.
 *
 * Default argument values live here rather than in the implementations, so a
 * call means the same thing whichever store answers it.
 */
interface LibraryStore {

    // ---------- settings ----------

    fun getSetting(key: String, default: String): String
    fun setSetting(key: String, value: String)

    // ---------- folders ----------

    fun folders(): List<MusicFolder>

    /**
     * [treeUri] is a SAF tree uri on Android and a `file:` uri on the desktop;
     * it is only ever compared and handed back to the platform, never parsed
     * by shared code.
     */
    fun addFolder(treeUri: String, displayPath: String): Long
    fun removeFolder(id: Long)

    // ---------- tracks ----------

    /**
     * Songs matching [query] in [sort] order. [limit] < 0 reads the whole
     * library; a non-negative limit windows the read so the songs tab can
     * render one page at a time instead of every row on every keystroke.
     */
    fun tracks(query: String = "", sort: String = "title", limit: Int = -1, offset: Int = 0): List<Track>

    /** How many songs [query] matches — the total behind a windowed read. */
    fun trackCount(query: String): Int

    fun trackCount(): Int

    fun track(id: Long): Track?

    /** Tracks looked up by id, returned in the order of [ids]; missing ids are skipped. */
    fun tracksByIds(ids: List<Long>): List<Track>

    fun tracksForAlbum(album: String, artist: String): List<Track>
    fun tracksForArtist(artist: String): List<Track>
    fun tracksForGenre(genre: String): List<Track>

    fun favorites(): List<Track>
    fun setFavorite(id: Long, fav: Boolean)

    fun upsertTrack(
        uri: String, parentUri: String, displayName: String, title: String, artist: String,
        album: String, durationMs: Long, trackNo: Int, genre: String, year: String,
        albumArtist: String, discNo: Int, composer: String,
        hasArt: Boolean, lrcUri: String?, folderId: Long, lastModified: Long
    )

    /** Drop rows for files that have disappeared from a scanned folder. */
    fun deleteMissingInFolder(folderId: Long, seenUris: Set<String>)

    fun updateTags(
        id: Long, title: String, artist: String, album: String, genre: String, year: String,
        trackNo: Int, albumArtist: String, discNo: Int, composer: String, comment: String
    )

    /**
     * Batch-editing counterpart to [updateTags]: null means "leave this column
     * alone", so a batch that only sets the artist can't blank out every other
     * field of the selected tracks.
     */
    fun updatePartialTags(
        id: Long, title: String? = null, artist: String? = null, album: String? = null,
        genre: String? = null, year: String? = null, trackNo: Int? = null, albumArtist: String? = null
    )

    /** Points a track row at its new file after a physical rename. */
    fun updateTrackFile(id: Long, uri: String, displayName: String)
    fun updateLrcUri(id: Long, lrcUri: String?)
    fun setHasArt(id: Long, hasArt: Boolean)
    fun removeTrack(id: Long)

    // ---------- albums / artists / genres ----------

    fun albums(sort: String = "title"): List<AlbumRow>
    fun artists(sort: String = "name"): List<ArtistRow>
    fun genres(sort: String = "name"): List<GenreRow>

    // ---------- playlists ----------

    fun createPlaylist(name: String): Long
    fun renamePlaylist(id: Long, name: String)
    fun deletePlaylist(id: Long)
    fun playlists(): List<Playlist>
    fun playlist(id: Long): Playlist?
    fun addToPlaylist(playlistId: Long, trackId: Long)
    fun removeFromPlaylist(playlistId: Long, trackId: Long)
    fun playlistTracks(playlistId: Long): List<Track>

    // ---------- listening journal ----------

    /** Append one listen to the plays log (see the player's play threshold). */
    fun recordPlay(trackId: Long, playedAt: Long)

    /** The whole Listening Journal in one read. */
    fun journalStats(): JournalStats
}
