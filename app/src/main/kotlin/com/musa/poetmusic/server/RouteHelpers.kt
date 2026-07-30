package com.musa.poetmusic.server

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.data.Track
import com.musa.poetmusic.playback.PlayerController
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.util.pipeline.PipelineContext

/**
 * Shared plumbing for the route groups: request parsing, the handful of
 * response shapes the htmx frontend understands, and the two lookups every
 * other handler starts with.
 *
 * These deliberately live here rather than in [Html] — that file is
 * dependency-free by design so its escaping rules can be unit-tested on a
 * plain JVM, and pulling Ktor into it would end that.
 */

// ---------- responses ----------

/**
 * The default response shape. Note the two distinct "nothing to show" replies
 * below: they are not interchangeable, and [emptyHtml] in particular is
 * load-bearing.
 */
internal suspend fun PipelineContext<Unit, ApplicationCall>.html(body: String) {
    call.respondText(body, ContentType.Text.Html)
}

/**
 * An empty *fragment*. Overlay roots (`#sheet-root`, `#modal-root`) are
 * cleared by swapping this in, so a handler that has nothing to show must
 * still answer 200 with a body — a 204 makes htmx skip the swap and leaves
 * the stale overlay on screen.
 */
internal suspend fun PipelineContext<Unit, ApplicationCall>.emptyHtml() {
    call.respondText("", ContentType.Text.Html)
}

/** No swap at all: the frontend keeps whatever is already rendered. */
internal suspend fun PipelineContext<Unit, ApplicationCall>.noContent() {
    call.respond(HttpStatusCode.NoContent)
}

internal suspend fun PipelineContext<Unit, ApplicationCall>.toast(msg: String) {
    call.response.header("HX-Trigger", """{"poet-toast":${jsonStr(msg)}}""")
    call.respond(HttpStatusCode.NoContent)
}

/** Accent-colored pill toast, used for Now Playing state changes. */
internal suspend fun PipelineContext<Unit, ApplicationCall>.toastAccent(msg: String) {
    call.response.header("HX-Trigger", """{"poet-toast-accent":${jsonStr(msg)}}""")
    call.respond(HttpStatusCode.NoContent)
}

internal suspend fun PipelineContext<Unit, ApplicationCall>.refresh(msg: String) {
    call.response.header("HX-Trigger", """{"poet-toast":${jsonStr(msg)},"poet-refresh":true}""")
    call.respond(HttpStatusCode.NoContent)
}

// ---------- lookups ----------

/**
 * The track named by the `{id}` path segment, or null.
 *
 * Deliberately responds *nothing* on a miss. Callers disagree about what a
 * miss means — a queue action answers 204, a sheet answers [emptyHtml] so the
 * overlay closes, and the media routes answer 404 — so the branch stays at
 * the call site.
 */
internal fun PipelineContext<Unit, ApplicationCall>.trackParam(db: MusicDatabase): Track? =
    call.parameters["id"]?.toLongOrNull()?.let(db::track)

/**
 * The row for whatever is loaded in the player right now, or null.
 *
 * Takes the snapshot as a parameter so a handler that also reads other fields
 * off it resolves the track from that same snapshot — `snapshot` is volatile
 * and re-read, so two reads in one handler can straddle a track change.
 */
internal fun currentTrack(
    db: MusicDatabase,
    s: PlayerController.Snapshot = PlayerController.snapshot
): Track? = s.trackId.takeIf { it >= 0 }?.let(db::track)

/**
 * Comma-separated track ids from a query parameter, non-numeric entries
 * dropped (see [parseIds]). Defaults to `ids`; the add-to-playlist route also
 * passes a `pids` list.
 */
internal fun PipelineContext<Unit, ApplicationCall>.idList(param: String = "ids"): List<Long> =
    parseIds(call.request.queryParameters[param] ?: "")

internal fun PipelineContext<Unit, ApplicationCall>.queueCtx(): QueueCtx {
    val p = call.request.queryParameters
    return QueueCtx(
        ctx = p["ctx"] ?: "songs",
        q = p["q"] ?: "",
        sort = p["sort"] ?: "title",
        album = p["album"] ?: "",
        artist = p["artist"] ?: "",
        pid = p["pid"]?.toLongOrNull() ?: 0
    )
}

// ---------- rendering / misc ----------

/** Re-rendered inner content of the queue panel (#qp-body). */
internal fun queueBody(db: MusicDatabase): String =
    QueueViews.queuePanelBody(db, PlayerController.queueItems(), PlayerController.snapshot.playing)

/** Human label for the listing a queue was built from ("Playing from …"). */
internal fun sourceLabel(db: MusicDatabase, ctx: QueueCtx): String = when (ctx.ctx) {
    "album" -> ctx.album.ifBlank { "Album" }
    "artist" -> ctx.artist.ifBlank { "Artist" }
    "playlist" -> db.playlist(ctx.pid)?.name ?: "Playlist"
    "favorites" -> "Favorites"
    else -> if (ctx.q.isNotBlank()) "search “${ctx.q}”" else "All songs"
}

/** File size in bytes for a SAF document uri, or -1 when unknown. */
internal fun fileSize(context: Context, uri: String): Long = try {
    context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
    } ?: -1L
} catch (e: Exception) {
    -1L
}
