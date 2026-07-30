package com.musa.poetmusic.server

/** Tiny helpers for building HTML/JSON safely from Kotlin string templates. */

fun esc(s: String): String = buildString(s.length) {
    for (ch in s) when (ch) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        else -> append(ch)
    }
}

fun jsonStr(s: String): String = buildString(s.length + 2) {
    append('"')
    for (ch in s) when (ch) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
    }
    append('"')
}

fun fmtTime(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${if (s < 10) "0$s" else "$s"}"
}

fun initials(name: String): String =
    name.split(" ", "-", "_").filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "♪" }

/** Pastel placeholder-art palette from the Poet design spec. */
val PASTEL_ARTS = listOf("#e6ddf7", "#d3ecdf", "#fbe3d4", "#d9e7f8", "#f7dce8", "#efe9d2", "#dfe3f4", "#e2f0ea")

fun artColor(key: Long): String = PASTEL_ARTS[((key % PASTEL_ARTS.size) + PASTEL_ARTS.size).toInt() % PASTEL_ARTS.size]

/** URL-encode a query value. */
fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

/**
 * Parse a comma-separated list of track ids, dropping anything that isn't a
 * number. Views interpolate the id string straight into HTML attributes and
 * inline JS (`onclick="poetAddPlaylists('…')"`), so routes must rebuild that
 * string from this parsed list rather than echoing the raw query value —
 * [idsParam] is the canonical way to do it.
 */
fun parseIds(raw: String): List<Long> =
    raw.split(",").mapNotNull { it.trim().toLongOrNull() }

/** Render a parsed id list back into a query-safe "1,2,3" string. */
fun idsParam(ids: List<Long>): String = ids.joinToString(",")
