package com.musa.poetmusic.server

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.musa.poetmusic.data.MusicDatabase
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Byte-serving routes: cover art and the audio stream the player reads from. */
internal fun Route.mediaRoutes(app: Context, db: MusicDatabase) {

    get("/api/art/{id}") {
        val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.NotFound)
        val cached = ArtCache.get(id)
        if (cached != null) {
            call.response.header("Cache-Control", "max-age=3600")
            return@get call.respondBytes(cached, ContentType.parse("image/jpeg"))
        }
        val track = db.track(id)
        var art: ByteArray? = null
        if (track != null && track.hasArt) {
            val mmr = MediaMetadataRetriever()
            art = try {
                mmr.setDataSource(app, Uri.parse(track.uri))
                mmr.embeddedPicture
            } catch (e: Exception) {
                null
            } finally {
                runCatching { mmr.release() }
            }
        }
        val ph = ArtCache.placeholder
        if (art != null) {
            ArtCache.put(id, art)
            call.response.header("Cache-Control", "max-age=3600")
            call.respondBytes(art, ContentType.parse("image/jpeg"))
        } else if (ph != null) {
            // Branded placeholder cover for tracks without artwork.
            call.response.header("Cache-Control", "max-age=3600")
            call.respondBytes(ph, ContentType.parse("image/jpeg"))
        } else {
            val bg = artColor(id)
            val label = esc(initials(track?.title ?: "?"))
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="240" height="240">""" +
                """<rect width="240" height="240" fill="$bg"/>""" +
                """<text x="120" y="136" font-family="sans-serif" font-size="52" font-weight="600" fill="rgba(59,54,81,0.5)" text-anchor="middle">$label</text></svg>"""
            call.response.header("Cache-Control", "max-age=3600")
            call.respondText(svg, ContentType.parse("image/svg+xml"))
        }
    }

    get("/api/stream/{id}") {
        val track = trackParam(db)
            ?: return@get call.respond(HttpStatusCode.NotFound)
        val mime = when (track.displayName.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
        val input = try {
            app.contentResolver.openInputStream(Uri.parse(track.uri))
        } catch (e: Exception) {
            null
        } ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondOutputStream(ContentType.parse(mime)) {
            input.use { it.copyTo(this) }
        }
    }
}
