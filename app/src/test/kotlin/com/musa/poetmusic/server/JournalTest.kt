package com.musa.poetmusic.server

import com.musa.poetmusic.data.JournalStats
import com.musa.poetmusic.data.PeakHour
import com.musa.poetmusic.data.TopArtist
import com.musa.poetmusic.data.TopTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Listening Journal reports numbers the user will compare against their
 * own library, so the derivations (hours, percentages, the empty states) are
 * pinned down here, along with the escaping of track and artist names.
 */
class JournalTest {

    private fun stats(
        trackCount: Int = 1420,
        albumCount: Int = 14,
        totalDurationMs: Long = 304_200_000,
        filledTagFields: Int = 8184,
        totalTagFields: Int = 8520,
        missingArt: Int = 18,
        syncedLyrics: Int = 45,
        totalPlays: Int = 100,
        topTrack: TopTrack? = TopTrack("Habits R3m1x", "ElPrince787", 42),
        topArtist: TopArtist? = TopArtist("PatrickxxLee", 128),
        peakHour: PeakHour? = PeakHour(23, 38)
    ) = JournalStats(
        trackCount, albumCount, totalDurationMs, filledTagFields, totalTagFields,
        missingArt, syncedLyrics, totalPlays, topTrack, topArtist, peakHour
    )

    private fun screen(s: JournalStats = stats()) = JournalViews.journalScreen(s, false, "0")

    @Test
    fun `hours are rendered to one decimal`() {
        assertEquals("0.0", JournalViews.fmtHours(0))
        assertEquals("1.0", JournalViews.fmtHours(3_600_000))
        assertEquals("84.5", JournalViews.fmtHours(304_200_000))
        assertEquals("0.5", JournalViews.fmtHours(1_800_000))
    }

    @Test
    fun `clock hour is zero padded and clamped`() {
        assertEquals("00:00", JournalViews.clockHour(0))
        assertEquals("09:00", JournalViews.clockHour(9))
        assertEquals("23:00", JournalViews.clockHour(23))
        // A stray hour from a corrupt row must not render as "24:00".
        assertEquals("23:00", JournalViews.clockHour(99))
        assertEquals("00:00", JournalViews.clockHour(-1))
    }

    @Test
    fun `hour labels cover the whole day`() {
        assertEquals("Small Hours", JournalViews.hourLabel(3))
        assertEquals("Morning", JournalViews.hourLabel(8))
        assertEquals("Afternoon", JournalViews.hourLabel(13))
        assertEquals("Evening", JournalViews.hourLabel(19))
        assertEquals("Late Night", JournalViews.hourLabel(23))
        for (h in 0..23) assertTrue("hour $h needs a label", JournalViews.hourLabel(h).isNotEmpty())
    }

    @Test
    fun `percent rounds and never divides by zero`() {
        assertEquals(0, JournalViews.percent(0, 0))
        assertEquals(0, JournalViews.percent(5, 0))
        assertEquals(38, JournalViews.percent(38, 100))
        assertEquals(96, JournalViews.percent(8184, 8520))
        assertEquals(100, JournalViews.percent(10, 10))
        // 1/3 rounds to 33, 2/3 to 67.
        assertEquals(33, JournalViews.percent(1, 3))
        assertEquals(67, JournalViews.percent(2, 3))
    }

    @Test
    fun `plural only drops the s for exactly one`() {
        assertEquals("track", JournalViews.plural(1, "track"))
        assertEquals("tracks", JournalViews.plural(0, "track"))
        assertEquals("tracks", JournalViews.plural(2, "track"))
    }

    @Test
    fun `the screen reports the library totals it was given`() {
        val html = screen()
        assertTrue(html.contains("1,420 tracks"))
        assertTrue(html.contains("14 albums"))
        assertTrue(html.contains("84.5 hours"))
        assertTrue(html.contains("""data-screen="journal""""))
    }

    @Test
    fun `heavy rotation reports the play leaders`() {
        val html = screen()
        assertTrue(html.contains("Habits R3m1x"))
        assertTrue(html.contains("ElPrince787"))
        assertTrue(html.contains("42 times"))
        assertTrue(html.contains("PatrickxxLee"))
        assertTrue(html.contains("128 total plays"))
        assertTrue(html.contains("23:00 (Late Night)"))
        // 38 of 100 logged plays fell in the peak hour.
        assertTrue(html.contains("38%"))
    }

    @Test
    fun `library health reports tag index art gaps and lyrics`() {
        val html = screen()
        assertTrue(html.contains("96%"))
        assertTrue(html.contains("18 tracks"))
        assertTrue(html.contains("45 tracks"))
    }

    @Test
    fun `a single play is counted in the singular`() {
        val html = screen(stats(topTrack = TopTrack("Solo", "Nobody", 1), totalPlays = 1, peakHour = PeakHour(7, 1)))
        assertTrue(html.contains("1 time</span>"))
        assertFalse(html.contains("1 times"))
    }

    @Test
    fun `a library with no plays yet shows the rotation empty state`() {
        val html = screen(stats(topTrack = null, topArtist = null, peakHour = null, totalPlays = 0))
        assertTrue(html.contains("Nothing in rotation yet"))
        // Library totals still render — only the play-derived block is empty.
        assertTrue(html.contains("1,420 tracks"))
    }

    @Test
    fun `an empty library points at the folder picker instead of zero stats`() {
        val empty = stats(
            trackCount = 0, albumCount = 0, totalDurationMs = 0, filledTagFields = 0,
            totalTagFields = 0, missingArt = 0, syncedLyrics = 0, totalPlays = 0,
            topTrack = null, topArtist = null, peakHour = null
        )
        val html = screen(empty)
        assertTrue(html.contains("Nothing has been scanned yet"))
        // Every health tile reports the empty library rather than a 0% score,
        // which would read as a problem with the tags instead of no tags.
        assertEquals(3, Regex("no tracks yet").findAll(html).count())
        assertFalse(html.contains("0%"))
        assertFalse(html.contains("0 tracks"))
    }

    @Test
    fun `a fully covered library says so instead of counting zero`() {
        val html = screen(stats(missingArt = 0))
        assertTrue(html.contains("every track covered"))
        assertFalse(html.contains("0 tracks missing"))
    }

    @Test
    fun `track and artist names are escaped`() {
        val html = screen(
            stats(
                topTrack = TopTrack("""<img src=x onerror="alert(1)">""", "A & B", 3),
                topArtist = TopArtist("""Q" onclick="evil()""", 9)
            )
        )
        assertFalse("a track title must not open a tag", html.contains("<img src=x"))
        assertTrue(html.contains("&lt;img src=x"))
        assertTrue(html.contains("A &amp; B"))
        assertFalse("""an artist name must not break out of an attribute""", html.contains("""Q" onclick"""))
    }

    @Test
    fun `the portrait is only requested once one has been stored`() {
        assertFalse(screen().contains("/api/journal/portrait"))
        val withPortrait = JournalViews.journalScreen(stats(), true, "1717000000000")
        assertTrue(withPortrait.contains("/api/journal/portrait?v=1717000000000"))
        // Both states open the picker.
        assertTrue(screen().contains("poetPickPortrait()"))
        assertTrue(withPortrait.contains("poetPickPortrait()"))
    }

    @Test
    fun `the back button returns to the library screen`() {
        assertTrue(screen().contains("""hx-get="/screens/library""""))
    }
}
