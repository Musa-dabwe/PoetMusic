package com.musa.poetmusic.data

/**
 * Audio mime type from a file name. Used both by the local streaming route and
 * by the share sheet, which has to tell the receiving app what it is getting.
 */
fun audioMime(displayName: String): String =
    when (displayName.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "aac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "wma" -> "audio/x-ms-wma"
        "mid" -> "audio/midi"
        else -> "audio/*"
    }
