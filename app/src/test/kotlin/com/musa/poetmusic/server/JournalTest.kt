package com.musa.poetmusic.server

import com.musa.poetmusic.data.FormatShare
import com.musa.poetmusic.data.JournalStats
import com.musa.poetmusic.data.PeakDay
import com.musa.poetmusic.data.PeakHour
import com.musa.poetmusic.data.TopAlbum
import com.musa.poetmusic.data.TopArtist
import com.musa.poetmusic.data.TopGenre
import com.musa.poetmusic.data.TopTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Listening Journal reports numbers the user will compare against their
 * own library, so the derivations (hours, percentages, the empty states) are
 * pinned down here, along with the depth toggle and the escaping of track,
 * artist and album names.
 */
class JournalTest {

    private fun songs(n: Int) = (1..n).map { TopTrack("Song $it", "Artist $it", 50 - it) }
    private fun artists(n: Int) = (1..n).map { TopArtist("Artist $it", 50 - it) }
    private fun albums(n: Int) = (1..n).map { TopAlbum("Album $it", "Artist $it", 40 - it, it.toLong()) }
    private fun genres(n: Int) = (1..n).map { TopGenre("Genre $it", 40 - it) }

    private fun stats(
        trackCount: Int = 1420,
        albumCount: Int = 14,
        totalDurationMs: Long = 304_200_000,
        filledTagFields: Int = 8184,
        totalTagFields: Int = 8520,
        missingArt: Int = 18,
        syncedLyrics: Int = 45,
        totalPlays: Int = 100,
        topSongs: List<TopTrack> = songs(10),
        topArtists: List<TopArtist> = artists(10),
        topAlbums: List<TopAlbum> = albums(10),
        topGenres: List<TopGenre> = genres(8),
        peakHour: PeakHour? = PeakHour(23, 38),
        peakDay: PeakDay? = PeakDay(5, 26),
        exploredTracks: Int = 341,
        longestStreak: Int = 7,
        formats: List<FormatShare> = listOf(FormatShare("mp3", 1207), FormatShare("flac", 142), FormatShare("m4a", 71)),
        topDecade: String = "2020s",
        folderCount: Int = 2
    ) = JournalStats(
        trackCount, albumCount, totalDurationMs, filledTagFields, totalTagFields,
        missingArt, syncedLyrics, totalPlays, topSongs, topArtists, topAlbums, topGenres,
        peakHour, peakDay, exploredTracks, longestStreak, formats, topDecade, folderCount
    )

    private fun screen(s: JournalStats = stats(), detail: Boolean = false) =
        JournalViews.journalScreen(s, false, "0", detail)

    // ---------- formatting ----------

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
    fun `day names follow SQLite's Sunday-first weekday numbering`() {
        assertEquals("Sunday", JournalViews.dayName(0))
        assertEquals("Friday", JournalViews.dayName(5))
        assertEquals("Saturday", JournalViews.dayName(6))
        // Out-of-range values clamp rather than crash the screen.
        assertEquals("Saturday", JournalViews.dayName(9))
        assertEquals("Sunday", JournalViews.dayName(-1))
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
    fun `format summary keeps the three commonest kinds`() {
        val f = listOf(
            FormatShare("mp3", 1207), FormatShare("flac", 142),
            FormatShare("m4a", 71), FormatShare("ogg", 0)
        )
        assertEquals("MP3 85% · FLAC 10% · M4A 5%", JournalViews.formatSummary(f, 1420))
        assertEquals("unknown", JournalViews.formatSummary(emptyList(), 1420))
        assertEquals("unknown", JournalViews.formatSummary(f, 0))
    }

    // ---------- the screen ----------

    @Test
    fun `the screen is a full-screen page carrying the library totals`() {
        val html = screen()
        assertTrue(html.contains("""data-screen="journal""""))
        assertTrue(html.contains("""class="screen journal-screen""""))
        // The card panel is gone; the body paints edge to edge instead.
        assertFalse(html.contains("journal-card"))
        assertTrue(html.contains("1,420"))
        assertTrue(html.contains("84.5"))
    }

    @Test
    fun `the leaderboards rank artists songs albums and genres`() {
        val html = screen()
        assertTrue(html.contains("Top Artists"))
        assertTrue(html.contains("Top Songs"))
        assertTrue(html.contains("Top Albums"))
        assertTrue(html.contains("Top Genres"))
        assertTrue(html.contains("Song 1"))
        assertTrue(html.contains("49×"))
        // Albums with embedded art show the cover rather than the placeholder.
        assertTrue(html.contains("/api/art/1"))
    }

    @Test
    fun `an album with no embedded art anywhere falls back to its initials`() {
        val html = screen(stats(topAlbums = listOf(TopAlbum("Neon Cathedral", "Wondaboy", 12, 0))))
        assertFalse(html.contains("/api/art/0"))
        assertTrue(html.contains("journal-album-initials"))
        assertTrue(html.contains(">NC<"))
    }

    @Test
    fun `show wrapped deepens the leaderboards and swaps the toggle`() {
        val short = screen()
        assertTrue(short.contains("Show Wrapped"))
        assertTrue(short.contains("""hx-get="/screens/journal?detail=1""""))
        assertTrue(short.contains("Song 5"))
        assertFalse("collapsed stops at five", short.contains("Song 6"))
        assertFalse(short.contains("Genre 7"))

        val deep = screen(detail = true)
        assertTrue(deep.contains("Show less"))
        assertTrue(deep.contains("""hx-get="/screens/journal""""))
        assertFalse("expanded must not re-request the deep view", deep.contains("detail=1"))
        assertTrue(deep.contains("Song 10"))
        assertTrue(deep.contains("Artist 10"))
        assertTrue(deep.contains("Genre 8"))
    }

    @Test
    fun `habits report the day hour streak and discovery split`() {
        val html = screen()
        assertTrue(html.contains("Friday"))
        assertTrue(html.contains("7 days"))
        assertTrue(html.contains("23:00"))
        // 341 first listens out of 100 logged plays is capped at 100%; the
        // fixture's realistic split is asserted separately below.
        assertTrue(html.contains("Discovery mix"))
        assertTrue(html.contains("2020s"))
    }

    @Test
    fun `the discovery split counts first listens against total plays`() {
        val html = screen(stats(totalPlays = 200, exploredTracks = 40))
        assertTrue(html.contains("20% new"))
        assertTrue(html.contains("80% were songs you had already played."))
    }

    @Test
    fun `a single streak day is counted in the singular`() {
        val html = screen(stats(longestStreak = 1))
        assertTrue(html.contains("1 day</div>"))
        assertFalse(html.contains("1 days"))
    }

    @Test
    fun `the explored bar compares heard tracks against the library`() {
        val html = screen()
        // 341 of 1,420 tracks.
        assertTrue(html.contains("24%"))
        assertTrue(html.contains("""style="width:24%;""""))
        assertTrue(html.contains("You have listened to 341 of your"))
    }

    @Test
    fun `library health reports tag index art gaps lyrics formats and backup`() {
        val html = screen()
        assertTrue(html.contains("96%"))
        assertTrue(html.contains("18 tracks"))
        assertTrue(html.contains("45 tracks"))
        assertTrue(html.contains("MP3 85%"))
        assertTrue(html.contains("2 folders"))
    }

    @Test
    fun `an unmapped library says so instead of counting zero folders`() {
        val html = screen(stats(folderCount = 0))
        assertTrue(html.contains("no folder mapped"))
        assertFalse(html.contains("0 folders"))
    }

    @Test
    fun `a fully covered library says so instead of counting zero`() {
        val html = screen(stats(missingArt = 0))
        assertTrue(html.contains("every track covered"))
        assertFalse(html.contains("0 tracks missing"))
    }

    // ---------- personality ----------

    @Test
    fun `personality follows the repeat share then the clock`() {
        // 100 plays over 20 distinct tracks: 80% repeats.
        assertEquals("The Devoted Fan", JournalViews.personalityFor(stats(exploredTracks = 20)).first)
        // 100 plays over 60 distinct tracks: 40% repeats.
        assertEquals("The Deep Diver", JournalViews.personalityFor(stats(exploredTracks = 60)).first)
        // Mostly first listens, but all of them late: the clock decides.
        assertEquals("The Night Owl", JournalViews.personalityFor(stats(exploredTracks = 90, peakHour = PeakHour(23, 40))).first)
        assertEquals("The Explorer", JournalViews.personalityFor(stats(exploredTracks = 90, peakHour = PeakHour(14, 40))).first)
        // Nothing logged at all.
        assertEquals("The Newcomer", JournalViews.personalityFor(stats(totalPlays = 0)).first)
    }

    @Test
    fun `the personality card is rendered with its description`() {
        val html = screen(stats(exploredTracks = 20))
        assertTrue(html.contains("Your listener personality"))
        assertTrue(html.contains("The Devoted Fan"))
        assertTrue(html.contains("80%"))
    }

    // ---------- empty states ----------

    @Test
    fun `a library with no plays yet shows the rotation empty state`() {
        val html = screen(
            stats(
                totalPlays = 0, topSongs = emptyList(), topArtists = emptyList(),
                topAlbums = emptyList(), topGenres = emptyList(),
                peakHour = null, peakDay = null, exploredTracks = 0, longestStreak = 0
            )
        )
        assertTrue(html.contains("Nothing in rotation yet"))
        assertFalse("no leaderboard should render without plays", html.contains("Top Songs"))
        assertFalse(html.contains("Listening Habits"))
        // Library totals still render — only the play-derived blocks are empty.
        assertTrue(html.contains("1,420"))
        assertTrue(html.contains("Tag Integrity Index"))
    }

    @Test
    fun `an empty library points at the folder picker instead of zero stats`() {
        val empty = JournalStats(
            trackCount = 0, albumCount = 0, totalDurationMs = 0, filledTagFields = 0,
            totalTagFields = 0, missingArt = 0, syncedLyrics = 0, totalPlays = 0
        )
        val html = screen(empty)
        assertTrue(html.contains("Nothing has been scanned yet"))
        // Every health tile reports the empty library rather than a 0% score,
        // which would read as a problem with the tags instead of no tags.
        assertEquals(3, Regex("no tracks yet").findAll(html).count())
        assertFalse(html.contains("0%"))
        assertFalse(html.contains("0 tracks"))
        // No progress bar to draw when there is nothing to explore.
        assertFalse(html.contains("Library explored"))
    }

    // ---------- safety ----------

    @Test
    fun `track artist album and genre names are escaped`() {
        val html = screen(
            stats(
                topSongs = listOf(TopTrack("""<img src=x onerror="alert(1)">""", "A & B", 3)),
                topArtists = listOf(TopArtist("""Q" onclick="evil()""", 9)),
                topAlbums = listOf(TopAlbum("""<script>x</script>""", "A & B", 4, 7)),
                topGenres = listOf(TopGenre("""<b>Pop</b>""", 5))
            )
        )
        assertFalse("a track title must not open a tag", html.contains("<img src=x"))
        assertTrue(html.contains("&lt;img src=x"))
        assertTrue(html.contains("A &amp; B"))
        assertFalse("""an artist name must not break out of an attribute""", html.contains("""Q" onclick"""))
        assertFalse("an album name must not open a script", html.contains("<script>x</script>"))
        assertFalse("a genre name must not open a tag", html.contains("<b>Pop</b>"))
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
    fun `the back control and the footer button both return to the library`() {
        val html = screen()
        assertTrue(html.contains("""hx-get="/screens/library""""))
        assertEquals(2, Regex("""hx-get="/screens/library"""").findAll(html).count())
    }
}
