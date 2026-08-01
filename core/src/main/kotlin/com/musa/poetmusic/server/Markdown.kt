package com.musa.poetmusic.server

/**
 * A small, dependency-free Markdown → HTML renderer for the in-app About
 * screen. It supports the subset the bundled docs use: ATX headings, fenced
 * code blocks, unordered / ordered lists, blockquotes, horizontal rules,
 * paragraphs, and the inline spans `**bold**`, `*italic*`, `` `code` `` and
 * `[label](url)`. All text is HTML-escaped before inline spans are applied,
 * so document content can never inject markup. Output is styled by the `.md`
 * rules in assets/web/poet.css so it follows the active (light / dark) theme.
 */
object Markdown {

    fun render(md: String): String {
        val out = StringBuilder("""<div class="md">""")
        val lines = md.replace("\r\n", "\n").split("\n")
        var i = 0
        var paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotBlank()) out.append("<p>").append(inline(paragraph.toString().trim())).append("</p>")
            paragraph = StringBuilder()
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // fenced code block
            if (trimmed.startsWith("```")) {
                flushParagraph()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(esc(lines[i])).append('\n'); i++
                }
                out.append("<pre><code>").append(code.toString().trimEnd('\n')).append("</code></pre>")
                i++
                continue
            }

            // blank line ends a paragraph
            if (trimmed.isEmpty()) { flushParagraph(); i++; continue }

            // horizontal rule
            if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                flushParagraph(); out.append("<hr>"); i++; continue
            }

            // heading
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
            if (heading != null) {
                flushParagraph()
                val level = heading.groupValues[1].length
                out.append("<h$level>").append(inline(heading.groupValues[2].trim())).append("</h$level>")
                i++; continue
            }

            // blockquote (consecutive '>' lines)
            if (trimmed.startsWith(">")) {
                flushParagraph()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quote.append(lines[i].trim().removePrefix(">").trim()).append(' '); i++
                }
                out.append("<blockquote>").append(inline(quote.toString().trim())).append("</blockquote>")
                continue
            }

            // unordered list
            if (Regex("^[-*+]\\s+").containsMatchIn(trimmed)) {
                flushParagraph()
                out.append("<ul>")
                while (i < lines.size && Regex("^[-*+]\\s+").containsMatchIn(lines[i].trim())) {
                    val item = lines[i].trim().replaceFirst(Regex("^[-*+]\\s+"), "")
                    out.append("<li>").append(inline(item)).append("</li>"); i++
                }
                out.append("</ul>")
                continue
            }

            // ordered list
            if (Regex("^\\d+\\.\\s+").containsMatchIn(trimmed)) {
                flushParagraph()
                out.append("<ol>")
                while (i < lines.size && Regex("^\\d+\\.\\s+").containsMatchIn(lines[i].trim())) {
                    val item = lines[i].trim().replaceFirst(Regex("^\\d+\\.\\s+"), "")
                    out.append("<li>").append(inline(item)).append("</li>"); i++
                }
                out.append("</ol>")
                continue
            }

            // paragraph text
            paragraph.append(line).append(' ')
            i++
        }
        flushParagraph()
        out.append("</div>")
        return out.toString()
    }

    /**
     * Inline formatting. The input is HTML-escaped first, then code spans,
     * links, bold and italic are applied by regex over the escaped text.
     */
    private fun inline(raw: String): String {
        var s = esc(raw)
        // `code`
        s = Regex("`([^`]+)`").replace(s) { "<code>${it.groupValues[1]}</code>" }
        // [label](url)
        s = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)").replace(s) { m ->
            val label = m.groupValues[1]
            val url = m.groupValues[2]
            if (url.startsWith("http://") || url.startsWith("https://"))
                """<a href="$url" target="_blank" rel="noopener">$label</a>"""
            else label
        }
        // **bold**
        s = Regex("\\*\\*([^*]+)\\*\\*").replace(s) { "<strong>${it.groupValues[1]}</strong>" }
        // *italic*
        s = Regex("(?<!\\*)\\*(?!\\*)([^*]+)\\*(?!\\*)").replace(s) { "<em>${it.groupValues[1]}</em>" }
        return s
    }
}
