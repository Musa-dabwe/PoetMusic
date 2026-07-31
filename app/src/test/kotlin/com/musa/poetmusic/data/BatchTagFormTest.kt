package com.musa.poetmusic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch editing is only safe because a blank field means "leave unchanged".
 * If [TagEditor.BatchForm.isEmpty] were ever wrong the other way, submitting an
 * untouched form would blank the tags of every selected track.
 */
class BatchTagFormTest {

    @Test
    fun `an untouched form is empty`() {
        assertTrue(TagEditor.BatchForm().isEmpty())
    }

    @Test
    fun `whitespace-only fields count as untouched`() {
        assertTrue(TagEditor.BatchForm(artist = "   ", album = "\t").isEmpty())
    }

    @Test
    fun `any single filled field makes the form non-empty`() {
        assertFalse(TagEditor.BatchForm(title = "x").isEmpty())
        assertFalse(TagEditor.BatchForm(artist = "x").isEmpty())
        assertFalse(TagEditor.BatchForm(album = "x").isEmpty())
        assertFalse(TagEditor.BatchForm(albumArtist = "x").isEmpty())
        assertFalse(TagEditor.BatchForm(genre = "x").isEmpty())
        assertFalse(TagEditor.BatchForm(year = "1999").isEmpty())
        assertFalse(TagEditor.BatchForm(trackNo = "3").isEmpty())
    }

    @Test
    fun `the result reports what actually happened`() {
        assertEquals("Tags written to 4 files", TagEditor.BatchResult(4, 0, 0).message)
        assertEquals("Tags written to 1 file", TagEditor.BatchResult(1, 0, 0).message)
        assertEquals(
            "Updated 3 in the library (file tags support MP3 only)",
            TagEditor.BatchResult(0, 3, 0).message
        )
        assertEquals("Updated 5 — 2 saved to the library only", TagEditor.BatchResult(3, 2, 0).message)
        assertEquals("Updated 3, 1 failed", TagEditor.BatchResult(3, 0, 1).message)
        assertEquals("Couldn't update any of the selected tracks", TagEditor.BatchResult(0, 0, 2).message)
    }

    @Test
    fun `touched counts both file writes and library-only updates`() {
        assertEquals(5, TagEditor.BatchResult(3, 2, 7).touched)
    }
}
