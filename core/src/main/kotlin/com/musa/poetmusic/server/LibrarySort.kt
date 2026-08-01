package com.musa.poetmusic.server

import com.musa.poetmusic.data.LibraryStore

/**
 * One row of a sort drawer: the URL [slug] the drawer fires, the database
 * sort [key] it maps to, and the [label] shown to the user.
 */
data class SortOption(val slug: String, val key: String, val label: String)

/**
 * The sort states each library tab exposes, and where the chosen one is
 * persisted. Every tab keeps its own setting (`sort_songs`, `sort_albums`, …)
 * so re-sorting Albums no longer disturbs the Songs order.
 */
object LibrarySort {

    val SONGS = listOf(
        SortOption("title-az", "title", "Title A-Z"),
        SortOption("title-za", "title_desc", "Title Z-A"),
        SortOption("artist-az", "artist", "Artist A-Z"),
        SortOption("artist-za", "artist_desc", "Artist Z-A"),
        SortOption("date-modified", "date_modified", "Date modified"),
        SortOption("recent", "recent", "Recently added"),
        SortOption("oldest", "date_added", "Oldest added"),
        SortOption("longest", "duration", "Longest first"),
        SortOption("shortest", "duration_asc", "Shortest first")
    )

    val ALBUMS = listOf(
        SortOption("album-title", "title", "Title A-Z"),
        SortOption("album-artist", "artist", "Artist A-Z"),
        SortOption("album-year", "year", "Year, newest first"),
        SortOption("album-tracks", "tracks", "Most tracks")
    )

    val ARTISTS = listOf(
        SortOption("artist-name", "name", "Name A-Z"),
        SortOption("artist-tracks", "tracks", "Most tracks")
    )

    val GENRES = listOf(
        SortOption("genre-name", "name", "Name A-Z"),
        SortOption("genre-tracks", "tracks", "Most tracks")
    )

    /** Tabs that carry a sort drawer; Playlists keeps its manual order. */
    val SORTABLE_TABS = listOf("songs", "albums", "artists", "genres")

    fun optionsFor(tab: String): List<SortOption> = when (tab) {
        "albums" -> ALBUMS
        "artists" -> ARTISTS
        "genres" -> GENRES
        else -> SONGS
    }

    fun titleFor(tab: String): String = when (tab) {
        "albums" -> "Sort albums by"
        "artists" -> "Sort artists by"
        "genres" -> "Sort genres by"
        else -> "Sort songs by"
    }

    fun defaultFor(tab: String): String = optionsFor(tab).first().key

    private fun settingKey(tab: String): String =
        "sort_" + (if (tab in SORTABLE_TABS) tab else "songs")

    /** The db sort key a drawer slug selects, falling back to the tab default. */
    fun keyForSlug(tab: String, slug: String): String =
        optionsFor(tab).firstOrNull { it.slug == slug }?.key ?: defaultFor(tab)

    /**
     * The persisted sort for [tab]. Songs fall back to the pre-split `lib_sort`
     * key so an upgrade keeps the order the user last chose; anything the tab
     * no longer offers degrades to its default rather than to an empty list.
     */
    fun read(db: LibraryStore, tab: String): String {
        val fallback = if (tab == "songs") db.getSetting("lib_sort", defaultFor(tab)) else defaultFor(tab)
        val stored = db.getSetting(settingKey(tab), fallback)
        return if (optionsFor(tab).any { it.key == stored }) stored else defaultFor(tab)
    }

    fun write(db: LibraryStore, tab: String, key: String) {
        db.setSetting(settingKey(tab), key)
        // Kept in step so a downgrade (or any leftover reader) still sees it.
        if (tab == "songs") db.setSetting("lib_sort", key)
    }
}
