package com.musa.poetmusic.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * QueueCtx.query() rebuilds the listing context carried through every row's
 * hx-* URLs. Values are URL-encoded, which is what keeps search terms and
 * album names from breaking out of the query string.
 */
class QueueCtxTest {

    @Test
    fun `default context emits only the ctx key`() {
        assertEquals("ctx=songs", QueueCtx().query())
    }

    @Test
    fun `default sort is omitted but a custom sort is included`() {
        assertEquals("ctx=songs", QueueCtx(sort = "title").query())
        assertEquals("ctx=songs&sort=artist_desc", QueueCtx(sort = "artist_desc").query())
    }

    @Test
    fun `album context carries album and artist`() {
        assertEquals(
            "ctx=album&album=Homogenic&artist=Bj%C3%B6rk",
            QueueCtx(ctx = "album", album = "Homogenic", artist = "Björk").query()
        )
    }

    @Test
    fun `playlist context carries a non-zero pid only`() {
        assertEquals("ctx=playlist&pid=12", QueueCtx(ctx = "playlist", pid = 12).query())
        assertEquals("ctx=playlist", QueueCtx(ctx = "playlist", pid = 0).query())
    }

    @Test
    fun `search terms are url-encoded`() {
        assertEquals("ctx=songs&q=a%26b", QueueCtx(q = "a&b").query())
    }

    @Test
    fun `hostile values cannot break out of the query string`() {
        val q = QueueCtx(q = """" onload="alert(1)""", album = "<script>").query()
        assertFalse("quotes must be encoded", q.contains('"'))
        assertFalse("angle brackets must be encoded", q.contains('<'))
        assertFalse(q.contains('>'))
    }
}
