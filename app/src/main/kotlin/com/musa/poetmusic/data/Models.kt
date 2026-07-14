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
