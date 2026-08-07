package com.musa.poetmusic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the file-renaming logic used by the tag editor when "Rename file from
 * tags" is toggled on. The pattern is user-supplied, so the function must
 * handle missing tokens, illegal filename characters, and length limits.
 */
class FileNameFromPatternTest {

    private fun rename(
        pattern: String = "",
        title: String = "My Song",
        artist: String = "Artist",
        album: String = "Album",
        trackNo: Int = 1,
        ext: String = "mp3"
    ) = fileNameFromPattern(pattern, title, artist, album, trackNo, ext)

    @Test
    fun `default pattern is track dash title`() {
        assertEquals("1 - My Song.mp3", rename())
    }

    @Test
    fun `blank title falls back to Untitled`() {
        assertEquals("1 - Untitled.mp3", rename(title = ""))
    }

    @Test
    fun `blank artist falls back to Unknown`() {
        assertEquals("Unknown - My Song.mp3", rename(pattern = "%artist% - %title%", artist = ""))
    }

    @Test
    fun `track number zero renders as 00`() {
        assertEquals("00 - My Song.mp3", rename(trackNo = 0))
    }

    @Test
    fun `custom pattern with all tokens`() {
        val result = rename(pattern = "%track% %artist% - %title% [%album%]")
        assertEquals("1 Artist - My Song [Album].mp3", result)
    }

    @Test
    fun `illegal filename characters are replaced with underscore`() {
        val result = rename(title = "Song: Part 1/2 <test>", ext = "flac")
        assertTrue(result.contains("Song_ Part 1_2 _test_"))
        assertTrue(result.endsWith(".flac"))
    }

    @Test
    fun `extension is not doubled when pattern already contains it`() {
        val result = rename(pattern = "%title%.mp3", ext = "mp3")
        assertEquals("My Song.mp3", result)
    }

    @Test
    fun `blank extension produces no dot`() {
        val result = rename(ext = "")
        assertEquals("1 - My Song", result)
    }

    @Test
    fun `result is trimmed and dots removed from end`() {
        val result = rename(pattern = "%title%.", ext = "mp3")
        assertEquals("My Song.mp3", result)
    }

    @Test
    fun `long titles are truncated to 120 characters`() {
        val longTitle = "A".repeat(200)
        val result = rename(title = longTitle)
        assertTrue(result.length <= 120 + ".mp3".length)
    }
}
