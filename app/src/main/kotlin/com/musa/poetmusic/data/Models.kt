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

data class AlbumRow(val album: String, val artist: String, val trackCount: Int, val artTrackId: Long)

data class ArtistRow(val artist: String, val trackCount: Int, val artTrackId: Long)

data class Playlist(val id: Long, val name: String, val trackCount: Int)

data class MusicFolder(val id: Long, val treeUri: String, val displayPath: String)

/** The most played track of all time, as counted in the plays log. */
data class TopTrack(val title: String, val artist: String, val plays: Int)

/** The artist with the most logged plays across the whole library. */
data class TopArtist(val artist: String, val plays: Int)

/** The hour of the day (local time, 0-23) that collected the most plays. */
data class PeakHour(val hour: Int, val plays: Int)

/**
 * Everything the Listening Journal reports, read from the library and the
 * plays log in one pass. The three "heavy rotation" fields are null until
 * something has actually been listened to.
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
    val topTrack: TopTrack?,
    val topArtist: TopArtist?,
    val peakHour: PeakHour?
)
