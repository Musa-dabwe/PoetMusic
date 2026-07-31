package com.musa.poetmusic.server

import com.musa.poetmusic.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paging keeps the rendered row count bounded on a large library. What matters
 * is that the sentinel appears exactly while rows remain, carries the offset
 * the next page starts at, and disappears on the last page — otherwise the
 * list either stops short of the library or never stops asking for more.
 */
class SongListPagingTest {

    private fun track(id: Long) = Track(
        id = id, uri = "content://doc/$id", parentUri = "", displayName = "$id.mp3",
        title = "Song $id", artist = "Artist", album = "Album", durationMs = 200_000,
        trackNo = 1, genre = "Ambient", year = "2024", albumArtist = "", discNo = 0,
        composer = "", comment = "", hasArt = false, lrcUri = null, folderId = 1,
        favorite = false, dateAdded = 0, lastModified = 0
    )

    private fun tracks(count: Int, from: Long = 1) = (from until from + count).map(::track)

    private fun rowCount(html: String) = Regex("""data-track-id="""").findAll(html).count()

    @Test
    fun `a full listing renders no sentinel`() {
        val html = SharedViews.songList(tracks(10), QueueCtx())
        assertEquals(10, rowCount(html))
        assertFalse("nothing left to load", html.contains("load-more"))
    }

    @Test
    fun `a partial first page offers the rest`() {
        val html = SharedViews.songList(tracks(60), QueueCtx(), offset = 0, total = 10_000)
        assertEquals(60, rowCount(html))
        assertTrue(html.contains("""class="load-more""""))
        assertTrue("next page starts after this one", html.contains("offset=60"))
        assertTrue("remaining count is shown", html.contains("9940 left"))
    }

    @Test
    fun `the last page closes the list`() {
        // 120 of 120 rendered: 60 already shown plus this final 60.
        val html = SharedViews.songListPage(tracks(60, from = 61), QueueCtx(), offset = 60, total = 120)
        assertEquals(60, rowCount(html))
        assertFalse(html.contains("load-more"))
    }

    @Test
    fun `a middle page re-arms the sentinel at the right offset`() {
        val html = SharedViews.songListPage(tracks(60, from = 61), QueueCtx(), offset = 60, total = 500)
        assertTrue(html.contains("offset=120"))
        assertTrue(html.contains("380 left"))
    }

    @Test
    fun `an empty listing says so instead of paging`() {
        val html = SharedViews.songList(emptyList(), QueueCtx())
        assertTrue(html.contains("No songs here yet"))
        assertFalse(html.contains("load-more"))
    }

    @Test
    fun `the sentinel carries the listing context so pages stay in the same queue`() {
        val ctx = QueueCtx(ctx = "genre", genre = "Trip Hop")
        val html = SharedViews.songList(tracks(60), ctx, offset = 0, total = 200)
        assertTrue(html.contains("ctx=genre"))
        assertTrue("genre is url-encoded", html.contains("genre=Trip+Hop"))
    }

    @Test
    fun `a page size worth of rows with no more rows behind it ends cleanly`() {
        val html = SharedViews.songList(tracks(SharedViews.PAGE_SIZE), QueueCtx(), 0, SharedViews.PAGE_SIZE)
        assertFalse(html.contains("load-more"))
    }
}
