package com.musa.poetmusic.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The About screen renders through this, so its escaping matters too. */
class MarkdownTest {

    @Test
    fun `renders headings at the right level`() {
        assertTrue(Markdown.render("# Title").contains("<h1>Title</h1>"))
        assertTrue(Markdown.render("## Sub").contains("<h2>Sub</h2>"))
        assertTrue(Markdown.render("###### Deep").contains("<h6>Deep</h6>"))
    }

    @Test
    fun `wraps output in the md container`() {
        val html = Markdown.render("text")
        assertTrue(html.startsWith("""<div class="md">"""))
        assertTrue(html.endsWith("</div>"))
    }

    @Test
    fun `renders paragraphs and inline spans`() {
        assertTrue(Markdown.render("plain text").contains("<p>plain text</p>"))
        assertTrue(Markdown.render("**bold**").contains("<strong>bold</strong>"))
        assertTrue(Markdown.render("*italic*").contains("<em>italic</em>"))
        assertTrue(Markdown.render("`code`").contains("<code>code</code>"))
    }

    @Test
    fun `renders unordered and ordered lists`() {
        val ul = Markdown.render("- one\n- two")
        assertTrue(ul.contains("<ul><li>one</li><li>two</li></ul>"))
        val ol = Markdown.render("1. one\n2. two")
        assertTrue(ol.contains("<ol><li>one</li><li>two</li></ol>"))
    }

    @Test
    fun `renders fenced code blocks with escaped content`() {
        val html = Markdown.render("```\n<script>\n```")
        assertTrue(html.contains("<pre><code>&lt;script&gt;</code></pre>"))
    }

    @Test
    fun `renders horizontal rules and blockquotes`() {
        assertTrue(Markdown.render("---").contains("<hr>"))
        assertTrue(Markdown.render("> quoted").contains("<blockquote>quoted</blockquote>"))
    }

    @Test
    fun `only http and https links become anchors`() {
        assertTrue(Markdown.render("[x](https://example.com)").contains("""<a href="https://example.com""""))
        // javascript: URLs are dropped down to their label.
        val js = Markdown.render("[click](javascript:alert(1))")
        assertFalse(js.contains("<a "))
        assertFalse(js.contains("javascript:"))
        assertTrue(js.contains("click"))
    }

    @Test
    fun `raw html in the source is escaped, never emitted`() {
        val html = Markdown.render("<script>alert(1)</script>")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `escaping happens before inline spans so markup cannot be smuggled`() {
        val html = Markdown.render("""**<img src=x onerror=alert(1)>**""")
        assertTrue(html.contains("<strong>"))
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("&lt;img"))
    }

    @Test
    fun `empty input still produces a valid container`() {
        assertEquals("""<div class="md"></div>""", Markdown.render(""))
    }
}
