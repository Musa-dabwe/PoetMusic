package com.musa.poetmusic.data

data class Track(
    val id: Long,
    val uri: String,
    val parentUri: String,
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val trackNo: Int,
    val genre: String,
    val year: String,
    val albumArtist: String,
    val discNo: Int,
    val composer: String,
    val comment: String,
    val hasArt: Boolean,
    val lrcUri: String?,
    val folderId: Long,
    val favorite: Boolean,
    val dateAdded: Long,
    val lastModified: Long
)

/** [year] is the earliest four-digit year tagged on the album, or empty. */
data class AlbumRow(
    val album: String, val artist: String, val trackCount: Int, val artTrackId: Long, val year: String = ""
)

data class ArtistRow(val artist: String, val trackCount: Int, val artTrackId: Long)

/** One tagged genre and how many library tracks carry it. */
data class GenreRow(val genre: String, val trackCount: Int, val artTrackId: Long)

data class Playlist(val id: Long, val name: String, val trackCount: Int)

data class MusicFolder(val id: Long, val treeUri: String, val displayPath: String)

/** The most played track of all time, as counted in the plays log. */
data class TopTrack(val title: String, val artist: String, val plays: Int)

/** The artist with the most logged plays across the whole library. */
data class TopArtist(val artist: String, val plays: Int)

/** The hour of the day (local time, 0-23) that collected the most plays. */
data class PeakHour(val hour: Int, val plays: Int)

/** The weekday (local time, 0 = Sunday) that collected the most plays. */
data class PeakDay(val day: Int, val plays: Int)

/**
 * An album ranked by logged plays. [artTrackId] is a track in the album that
 * actually carries embedded art, or 0 when none of them do — the journal falls
 * back to a tinted tile in that case.
 */
data class TopAlbum(val album: String, val artist: String, val plays: Int, val artTrackId: Long)

/** A genre ranked by logged plays. */
data class TopGenre(val genre: String, val plays: Int)

/** How many tracks in the library carry a given file extension ("mp3"). */
data class FormatShare(val ext: String, val count: Int)

/**
 * Everything the Listening Journal reports, read from the library and the
 * plays log in one pass. The play-derived lists are empty and the peak
 * slots null until something has actually been listened to.
 */
data class JournalStats(
    val trackCount: Int,
    val albumCount: Int,
    val totalDurationMs: Long,
    /** Core tag fields (artist, album, genre, year, track no, album artist) that are filled in. */
    val filledTagFields: Int,
    /** Core tag fields the library could hold in total — [trackCount] × the six fields. */
    val totalTagFields: Int,
    val missingArt: Int,
    val syncedLyrics: Int,
    val totalPlays: Int,
    /** Play leaders, longest list first — the journal slices these for its "Wrapped" toggle. */
    val topSongs: List<TopTrack> = emptyList(),
    val topArtists: List<TopArtist> = emptyList(),
    val topAlbums: List<TopAlbum> = emptyList(),
    val topGenres: List<TopGenre> = emptyList(),
    val peakHour: PeakHour? = null,
    val peakDay: PeakDay? = null,
    /** Distinct library tracks with at least one logged play. */
    val exploredTracks: Int = 0,
    /** Longest run of consecutive local days that each logged at least one play. */
    val longestStreak: Int = 0,
    /** Extension breakdown of the library, commonest first. */
    val formats: List<FormatShare> = emptyList(),
    /** Decade the year tags point at ("2020s"), or empty when nothing is tagged. */
    val topDecade: String = "",
    /** Music folders mapped in Settings — the journal's backup line. */
    val folderCount: Int = 0
) {
    val topSong: TopTrack? get() = topSongs.firstOrNull()
    val topArtist: TopArtist? get() = topArtists.firstOrNull()
}
