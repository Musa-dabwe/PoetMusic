package com.musa.poetmusic.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the CSS/JS extraction: the page must still pull in the two asset
 * files, in the right order relative to the inline bootstrap, and must not
 * regress to carrying the stylesheet or the glue script inline.
 */
class ShellTest {

    private fun page(accent: String = "#b9a5ec", theme: String = "Lavender", dark: Boolean = false) =
        Shell.page(accent, theme, folderCount = 0, dark = dark)

    @Test
    fun `page links the external stylesheet and script`() {
        val html = page()
        assertTrue(html.contains("""<link rel="stylesheet" href="/assets/poet.css">"""))
        assertTrue(html.contains("""<script src="/assets/poet.js"></script>"""))
    }

    @Test
    fun `the inline bootstrap runs before the external script`() {
        val html = page()
        val bootstrap = html.indexOf("window.POET")
        val script = html.indexOf("""<script src="/assets/poet.js">""")
        assertTrue("bootstrap must be present", bootstrap >= 0)
        assertTrue("external script must be present", script >= 0)
        assertTrue("bootstrap must precede the external script", bootstrap < script)
    }

    @Test
    fun `the inline style precedes the stylesheet link so poet_css can override`() {
        val html = page()
        val inlineStyle = html.indexOf("<style>")
        val link = html.indexOf("""<link rel="stylesheet"""")
        assertTrue(inlineStyle in 0 until link)
    }

    @Test
    fun `theme variables are still rendered inline per request`() {
        val html = page(accent = "#123456")
        assertTrue(html.contains("--accent: #123456;"))
        assertTrue(html.contains("--accent-faint: #1234562e;"))
        // The canvas tint for the named theme resolves to its hex.
        assertTrue(html.contains("--bg: #f2effa;"))
    }

    @Test
    fun `an unknown theme falls back to lavender`() {
        assertTrue(page(theme = "Nonexistent").contains("--bg: #f2effa;"))
    }

    @Test
    fun `dark mode sets the theme attribute on the html element`() {
        assertTrue(page(dark = true).contains("""<html lang="en" data-theme="dark">"""))
        assertTrue(page(dark = false).contains("""<html lang="en">"""))
        assertTrue(page(dark = true).contains(""""dark": true""".replace(""""dark": """, """dark: """)))
    }

    @Test
    fun `bulk css and js no longer live in the kotlin string`() {
        val html = page()
        // A rule from the middle of poet.css and a function from poet.js.
        assertFalse("stylesheet body must not be inline", html.contains(".row-menu-btn"))
        assertFalse("glue script must not be inline", html.contains("function poetPoll"))
        // But the shell's own markup is still there.
        assertTrue(html.contains("""<div id="main-container"></div>"""))
        assertTrue(html.contains("""<div id="tray">"""))
    }

    @Test
    fun `main container does not self-load (Bugs md 3 1)`() {
        val html = page()
        val container = """<div id="main-container"></div>"""
        assertTrue(html.contains(container))
        // Any hx-trigger="load" on this element would re-introduce the race
        // that swapped the onboarding tip away.
        assertFalse(html.contains("""id="main-container" hx-trigger"""))
        assertFalse(html.contains("""hx-trigger="load""""))
    }

    @Test
    fun `no confirm or alert dialogs in served markup (Bugs md 3 5)`() {
        val html = page()
        assertFalse(html.contains("hx-confirm"))
        assertFalse(html.contains("window.confirm"))
        assertFalse(Regex("""[^a-zA-Z.]alert\(""").containsMatchIn(html))
        assertFalse(Regex("""[^a-zA-Z.]prompt\(""").containsMatchIn(html))
    }

    @Test
    fun `accent is json-encoded into the bootstrap`() {
        // Accent is regex-validated upstream, but the bootstrap should still
        // quote it rather than splice it raw.
        assertTrue(page(accent = "#abcdef").contains("""accent: "#abcdef""""))
    }

    @Test
    fun `the brand mark opens the listening journal`() {
        assertTrue(page().contains("""<div class="hdr-brand" onclick="poetGo('/screens/journal')">"""))
    }

    @Test
    fun `the header logo is the poet mark rather than a letter`() {
        val html = page()
        assertFalse("the letter mark is gone", html.contains("""<div class="hdr-logo">P</div>"""))
        assertTrue(html.contains("""<div class="hdr-logo"><svg viewBox="0 0 512 512""""))
        // The frame plus three bars, all inheriting .hdr-logo's ink colour so
        // the mark stays legible on every accent and in either theme.
        assertEquals(4, Regex("currentColor").findAll(Shell.LOGO_SVG).count())
        assertTrue(Shell.LOGO_SVG.contains("""style="width:100%; height:100%;""""))
    }

    @Test
    fun `the journal metric inset follows the selected canvas tint`() {
        assertTrue(page(theme = "Cream").contains("--inset-bg: #faf5ec;"))
    }

    @Test
    fun `shuffle and repeat icon arrays are sized for their mode indices`() {
        val html = page()
        assertEquals(1, Regex("""var ICON_SHUFFLE = \[""").findAll(html).count())
        assertEquals(1, Regex("""var ICON_REPEAT = \[""").findAll(html).count())
        // Repeat is indexed by mode 0..3, so the array needs four slots.
        val repeat = html.substringAfter("var ICON_REPEAT = [").substringBefore("];")
        assertEquals(4, repeat.split("', '").size)
    }
}
