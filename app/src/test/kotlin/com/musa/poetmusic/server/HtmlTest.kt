package com.musa.poetmusic.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escaping helpers are the last line of defence for every server-rendered
 * string, so they are pinned down here character by character.
 */
class HtmlTest {

    @Test
    fun `esc escapes all five significant characters`() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", esc("""&<>"'"""))
    }

    @Test
    fun `esc leaves ordinary text untouched`() {
        assertEquals("Björk — Jóga (1997)", esc("Björk — Jóga (1997)"))
        assertEquals("", esc(""))
    }

    @Test
    fun `esc neutralises an attribute breakout`() {
        // A track title crafted to escape a value="..." attribute.
        val escaped = esc("""" onerror="alert(1)""")
        assertTrue("quotes must not survive", !escaped.contains('"'))
        assertEquals("""&quot; onerror=&quot;alert(1)""", escaped)
    }

    @Test
    fun `esc neutralises a tag injection`() {
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;", esc("<script>alert(1)</script>"))
    }

    @Test
    fun `jsonStr quotes and escapes control characters`() {
        assertEquals(""""plain"""", jsonStr("plain"))
        assertEquals("""""""", jsonStr(""))
        assertEquals(""""a\"b"""", jsonStr("""a"b"""))
        assertEquals(""""a\\b"""", jsonStr("""a\b"""))
        assertEquals(""""a\nb\r\tc"""", jsonStr("a\nb\r\tc"))
    }

    @Test
    fun `jsonStr escapes other control characters as unicode`() {
        assertEquals("\"a\\u0001b\"", jsonStr("a\u0001b"))
        assertEquals("\"\\u001f\"", jsonStr("\u001f"))
        // Space is the first non-control character and must pass through.
        assertEquals("\" \"", jsonStr(" "))
    }

    @Test
    fun `jsonStr output cannot break out of an HX-Trigger payload`() {
        val payload = """{"poet-toast":${jsonStr("""x","evil":"y""")}}"""
        // Exactly one key/value pair survives: the injected quotes are escaped.
        assertEquals("""{"poet-toast":"x\",\"evil\":\"y"}""", payload)
    }

    @Test
    fun `enc percent-encodes query values`() {
        assertEquals("a%26b", enc("a&b"))
        assertEquals("%3Cscript%3E", enc("<script>"))
        assertEquals("hello+world", enc("hello world"))
    }

    @Test
    fun `fmtTime renders minutes and zero-padded seconds`() {
        assertEquals("0:00", fmtTime(0))
        assertEquals("0:05", fmtTime(5_000))
        assertEquals("0:59", fmtTime(59_999))
        assertEquals("1:00", fmtTime(60_000))
        assertEquals("3:07", fmtTime(187_000))
        assertEquals("100:00", fmtTime(6_000_000))
    }

    @Test
    fun `initials takes up to two uppercase letters`() {
        assertEquals("PM", initials("poet music"))
        assertEquals("AB", initials("alpha-beta"))
        assertEquals("AB", initials("alpha_beta"))
        assertEquals("AB", initials("Alpha Beta Gamma"))
        assertEquals("S", initials("solo"))
    }

    @Test
    fun `initials falls back to a note glyph for blank input`() {
        assertEquals("♪", initials(""))
        assertEquals("♪", initials("   "))
    }

    @Test
    fun `artColor is stable and always in palette`() {
        assertEquals(artColor(42), artColor(42))
        for (id in -50L..50L) {
            assertTrue("artColor($id) must be a palette entry", artColor(id) in PASTEL_ARTS)
        }
    }

    @Test
    fun `artColor handles negative ids without an index error`() {
        // Long.MIN_VALUE is the classic overflow case for a naive abs().
        assertTrue(artColor(Long.MIN_VALUE) in PASTEL_ARTS)
    }
}
