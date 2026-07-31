package com.musa.poetmusic.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sort slugs arrive from the URL, so an unknown one must degrade to the tab's
 * default rather than producing a sort key that reaches SQL — the key is
 * interpolated into an ORDER BY clause.
 */
class LibrarySortTest {

    @Test
    fun `each sortable tab has options and a default drawn from them`() {
        LibrarySort.SORTABLE_TABS.forEach { tab ->
            val options = LibrarySort.optionsFor(tab)
            assertTrue("$tab must offer sorts", options.isNotEmpty())
            assertEquals("$tab default is its first option", options.first().key, LibrarySort.defaultFor(tab))
        }
    }

    @Test
    fun `slugs are unique within a tab and resolve to their own key`() {
        LibrarySort.SORTABLE_TABS.forEach { tab ->
            val options = LibrarySort.optionsFor(tab)
            assertEquals("$tab has duplicate slugs", options.size, options.map { it.slug }.toSet().size)
            options.forEach { opt ->
                assertEquals(opt.key, LibrarySort.keyForSlug(tab, opt.slug))
            }
        }
    }

    @Test
    fun `an unknown slug falls back to the tab default`() {
        assertEquals("title", LibrarySort.keyForSlug("songs", "nonsense"))
        assertEquals("title", LibrarySort.keyForSlug("albums", ""))
        assertEquals("name", LibrarySort.keyForSlug("artists", "'; DROP TABLE tracks--"))
        assertEquals("name", LibrarySort.keyForSlug("genres", "album-year"))
    }

    @Test
    fun `an unknown tab is treated as songs`() {
        assertEquals(LibrarySort.SONGS, LibrarySort.optionsFor("playlists"))
        assertEquals("title", LibrarySort.defaultFor("nonsense"))
    }

    @Test
    fun `a slug from another tab never leaks across`() {
        // "album-year" is only meaningful for albums; asking songs for it must
        // not return a key the songs query has no ORDER BY for.
        val key = LibrarySort.keyForSlug("songs", "album-year")
        assertEquals("title", key)
        assertTrue(LibrarySort.SONGS.any { it.key == key })
    }

    @Test
    fun `songs expose both directions of date added and duration`() {
        val keys = LibrarySort.SONGS.map { it.key }
        assertTrue("recently added", "recent" in keys)
        assertTrue("oldest added", "date_added" in keys)
        assertTrue("longest first", "duration" in keys)
        assertTrue("shortest first", "duration_asc" in keys)
    }

    @Test
    fun `albums sort by title artist year and track count`() {
        val keys = LibrarySort.ALBUMS.map { it.key }
        assertEquals(listOf("title", "artist", "year", "tracks"), keys)
    }

    @Test
    fun `every option carries a label and the titles name their tab`() {
        LibrarySort.SORTABLE_TABS.forEach { tab ->
            LibrarySort.optionsFor(tab).forEach { assertFalse(it.label.isBlank()) }
            assertNotNull(LibrarySort.titleFor(tab))
            assertTrue(LibrarySort.titleFor(tab).contains(tab))
        }
    }
}
