package com.musa.poetmusic.data

data class LyricLine(val atMs: Long, val text: String)

/**
 * Parses .lrc timed lyric files ("[mm:ss.xx] line", multiple timestamps per
 * line allowed).
 *
 * Takes text rather than a uri: reading the bytes is a [HostPort] job, since
 * a SAF document and a `file:` path are opened very differently, but the
 * parsing is identical on both.
 */
object LrcParser {

    private val timeTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(text: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (raw in text.lineSequence()) {
            val tags = timeTag.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val content = raw.substring(tags.last().range.last + 1).trim()
            if (content.isEmpty()) continue
            for (tag in tags) {
                val min = tag.groupValues[1].toLong()
                val sec = tag.groupValues[2].toLong()
                val fracRaw = tag.groupValues[3]
                val ms = when (fracRaw.length) {
                    0 -> 0L
                    1 -> fracRaw.toLong() * 100
                    2 -> fracRaw.toLong() * 10
                    else -> fracRaw.take(3).toLong()
                }
                lines += LyricLine(min * 60_000 + sec * 1000 + ms, content)
            }
        }
        return lines.sortedBy { it.atMs }
    }
}
