package com.musa.poetmusic.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `ids` query parameter is interpolated into hx-* attributes and into
 * inline JS string literals by the options drawer, so routes must never pass
 * the raw value through. These tests pin the sanitising contract.
 */
class TrackIdsTest {

    @Test
    fun `parseIds reads a plain list`() {
        assertEquals(listOf(1L, 2L, 3L), parseIds("1,2,3"))
        assertEquals(listOf(7L), parseIds("7"))
    }

    @Test
    fun `parseIds tolerates surrounding whitespace`() {
        assertEquals(listOf(1L, 2L), parseIds(" 1 , 2 "))
    }

    @Test
    fun `parseIds drops every non-numeric entry`() {
        assertEquals(emptyList<Long>(), parseIds(""))
        assertEquals(emptyList<Long>(), parseIds("abc"))
        assertEquals(listOf(1L), parseIds("1,,"))
        assertEquals(listOf(1L, 2L), parseIds("1,oops,2"))
    }

    @Test
    fun `parseIds keeps negative ids as numbers`() {
        // Not valid track ids, but they are numeric and cannot carry markup.
        assertEquals(listOf(-1L), parseIds("-1"))
    }

    @Test
    fun `round-tripping an injection payload strips it entirely`() {
        // The payload survives the emptiness guard (id 1 parses) but must not
        // survive into the string the views interpolate.
        val hostile = """1,'); alert(1); //"""
        val safe = idsParam(parseIds(hostile))
        assertEquals("1", safe)
        assertTrue("no quotes may survive", !safe.contains('\''))
        assertTrue("no angle brackets may survive", !safe.contains('<'))
    }

    @Test
    fun `round-tripping an attribute breakout strips it entirely`() {
        val hostile = """2," onmouseover="alert(1)"""
        assertEquals("2", idsParam(parseIds(hostile)))
    }

    @Test
    fun `idsParam output only ever contains digits commas and minus signs`() {
        val safe = idsParam(parseIds("1,2,<img src=x onerror=alert(1)>,3"))
        assertEquals("1,2,3", safe)
        assertTrue(safe.all { it.isDigit() || it == ',' || it == '-' })
    }

    @Test
    fun `idsParam of an empty list is empty`() {
        assertEquals("", idsParam(emptyList()))
    }
}
