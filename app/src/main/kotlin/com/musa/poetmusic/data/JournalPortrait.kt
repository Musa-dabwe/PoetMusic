package com.musa.poetmusic.data

import android.content.Context
import java.io.File

/**
 * The portrait shown on the Listening Journal. The picked image is copied into
 * the app's private files directory rather than referenced by content uri, so
 * it survives the source photo being moved or its permission grant lapsing.
 * The db only carries the mime type and a stamp used to bust the WebView's
 * image cache after a replacement.
 */
object JournalPortrait {

    private const val FILE_NAME = "journal_portrait.img"
    private const val MIME_KEY = "journal_portrait_mime"
    private const val STAMP_KEY = "journal_portrait_at"

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).isFile

    /** Cache-busting token that changes whenever the portrait is replaced. */
    fun stamp(db: MusicDatabase): String = db.getSetting(STAMP_KEY, "0")

    fun mime(db: MusicDatabase): String = db.getSetting(MIME_KEY, "image/jpeg")

    fun read(context: Context): ByteArray? {
        val f = file(context)
        return if (f.isFile) runCatching { f.readBytes() }.getOrNull() else null
    }

    /** Replace the stored portrait; returns false if the image can't be written. */
    fun save(context: Context, db: MusicDatabase, bytes: ByteArray, mime: String): Boolean {
        val ok = runCatching { file(context).writeBytes(bytes) }.isSuccess
        if (ok) {
            db.setSetting(MIME_KEY, mime)
            db.setSetting(STAMP_KEY, System.currentTimeMillis().toString())
        }
        return ok
    }
}
