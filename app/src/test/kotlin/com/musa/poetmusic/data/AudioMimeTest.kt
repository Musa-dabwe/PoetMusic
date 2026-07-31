package com.musa.poetmusic.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The type the share sheet and the local stream route both announce. */
class AudioMimeTest {

    @Test
    fun `known extensions map to their type`() {
        assertEquals("audio/mpeg", audioMime("song.mp3"))
        assertEquals("audio/flac", audioMime("song.flac"))
        assertEquals("audio/mp4", audioMime("song.m4a"))
        assertEquals("audio/mp4", audioMime("song.aac"))
        assertEquals("audio/ogg", audioMime("song.opus"))
        assertEquals("audio/wav", audioMime("song.wav"))
    }

    @Test
    fun `matching is case-insensitive and uses the last extension`() {
        assertEquals("audio/mpeg", audioMime("SONG.MP3"))
        assertEquals("audio/flac", audioMime("a.mp3.backup.flac"))
    }

    @Test
    fun `an unknown or missing extension degrades to a generic audio type`() {
        assertEquals("audio/*", audioMime("song.xyz"))
        assertEquals("audio/*", audioMime("song"))
        assertEquals("audio/*", audioMime(""))
    }
}
