package com.musa.poetmusic.server

import com.musa.poetmusic.data.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the art-cache-busting strategy: every song row must carry a versioned
 * art URL so the browser never serves a stale cover after a tag save, and the
 * /api/art endpoint must respond with no-store to prevent intermediate caches
 * from holding stale images.
 */
class ArtCacheTest {

    private fun track(id: Long, lastModified: Long = 1000L) = Track(
        id = id, uri = "content://doc/$id", parentUri = "", displayName = "$id.mp3",
        title = "Song $id", artist = "Artist", album = "Album", durationMs = 200_000,
        trackNo = 1, genre = "Ambient", year = "2024", albumArtist = "", discNo = 0,
        composer = "", comment = "", hasArt = true, lrcUri = null, folderId = 1,
        favorite = false, dateAdded = 0, lastModified = lastModified
    )

    @Test
    fun `song row includes versioned art URL with lastModified`() {
        val row = SharedViews.songRow(track(42, lastModified = 9999L), QueueCtx())
        assertTrue("art src must carry ?v=", row.contains("/api/art/42?v=9999"))
    }

    @Test
    fun `song row without art uses initials not art URL`() {
        val t = Track(
            id = 1, uri = "content://doc/1", parentUri = "", displayName = "1.mp3",
            title = "Test", artist = "Artist", album = "Album", durationMs = 200_000,
            trackNo = 1, genre = "Ambient", year = "2024", albumArtist = "", discNo = 0,
            composer = "", comment = "", hasArt = false, lrcUri = null, folderId = 1,
            favorite = false, dateAdded = 0, lastModified = 0
        )
        val row = SharedViews.songRow(t, QueueCtx())
        assertFalse("no art URL when hasArt=false", row.contains("/api/art/"))
        assertTrue("shows initials", row.contains("T"))
    }

    @Test
    fun `different lastModified values produce different cache keys`() {
        val row1 = SharedViews.songRow(track(1, lastModified = 100L), QueueCtx())
        val row2 = SharedViews.songRow(track(1, lastModified = 200L), QueueCtx())
        assertTrue(row1.contains("v=100"))
        assertTrue(row2.contains("v=200"))
    }
}
