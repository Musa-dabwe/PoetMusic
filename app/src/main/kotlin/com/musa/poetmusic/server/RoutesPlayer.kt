package com.musa.poetmusic.server

import android.content.Context
import com.musa.poetmusic.data.LrcParser
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.playback.PlayerController
import com.musa.poetmusic.widget.WidgetRenderer
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Transport controls, the state poller's endpoint, and queue manipulation. */
internal fun Route.playerRoutes(app: Context, db: MusicDatabase) {

    get("/api/player/state") {
        val s = PlayerController.snapshot
        val track = currentTrack(db, s)
        val fav = track?.favorite == true
        val mod = track?.lastModified ?: 0
        val json = """{"trackId":${s.trackId},"title":${jsonStr(s.title)},"artist":${jsonStr(s.artist)},""" +
            """"pos":${s.positionMs},"dur":${s.durationMs},"playing":${s.playing},"shuffle":${s.shuffleMode},""" +
            """"repeat":${s.repeatMode},"speed":${s.speed},"sleep":${s.sleepRemainingMs},"sleepSongs":${s.sleepSongsRemaining},"hasQueue":${s.hasQueue},"fav":$fav,"mod":$mod}"""
        call.respondText(json, ContentType.Application.Json)
    }

    post("/api/player/play/{id}") {
        val id = call.parameters["id"]?.toLongOrNull() ?: return@post noContent()
        val ctx = queueCtx()
        val tracks = ctx.resolve(db)
        val index = tracks.indexOfFirst { it.id == id }
        if (index >= 0) PlayerController.setQueue(tracks, index, shuffled = false, source = sourceLabel(db, ctx))
        else db.track(id)?.let { PlayerController.setQueue(listOf(it), 0, shuffled = false, source = sourceLabel(db, ctx)) }
        noContent()
    }

    post("/api/queue/replace") {
        val ctx = queueCtx()
        val shuffle = call.request.queryParameters["shuffle"] == "1"
        val tracks = ctx.resolve(db)
        if (tracks.isEmpty()) return@post toast("Nothing to play yet")
        PlayerController.setQueue(tracks, 0, shuffled = shuffle, source = sourceLabel(db, ctx))
        noContent()
    }

    post("/api/queue/next/{id}") {
        val t = trackParam(db) ?: return@post noContent()
        PlayerController.playNext(t)
        toast("Playing next: ${t.title}")
    }

    post("/api/queue/add/{id}") {
        val t = trackParam(db) ?: return@post noContent()
        PlayerController.addToQueue(t)
        toast("Added to queue: ${t.title}")
    }

    /** Queue panel actions: each mutates the queue, then returns the
     *  re-rendered #qp-body fragment (queueItems() runs after the
     *  mutation on the FIFO main-thread handler, so it sees the result). */

    post("/api/queue/jump/{index}") {
        call.parameters["index"]?.toIntOrNull()?.let(PlayerController::jumpTo)
        html(queueBody(db))
    }

    post("/api/queue/remove/{index}") {
        call.parameters["index"]?.toIntOrNull()?.let(PlayerController::removeQueueItem)
        html(queueBody(db))
    }

    post("/api/queue/move") {
        val p = call.receiveParameters()
        val from = p["from"]?.toIntOrNull()
        val to = p["to"]?.toIntOrNull()
        if (from != null && to != null) PlayerController.moveQueueItem(from, to)
        html(queueBody(db))
    }

    post("/api/queue/clear") {
        PlayerController.clearUpcoming()
        html(queueBody(db))
    }

    post("/api/player/favourite") {
        val t = currentTrack(db) ?: return@post toast("Nothing is playing")
        db.setFavorite(t.id, !t.favorite)
        // The full-size widget shows the heart; keep it honest.
        WidgetRenderer.pushUpdate(app)
        toast(if (t.favorite) "Removed from Favourites" else "Added to Favourites ♥")
    }

    post("/api/player/toggle") { PlayerController.togglePlay(); noContent() }
    post("/api/player/next") { PlayerController.next(); noContent() }
    post("/api/player/prev") { PlayerController.previous(); noContent() }
    /* Musicolet-style state machines: one serialized controller op
       advances the mode and returns what was actually committed; the
       response is the button in that state (swapped in place via
       hx-swap="outerHTML") so the tap gets instant feedback instead
       of waiting for the poller. */
    post("/api/player/shuffle") {
        val mode = PlayerController.advanceShuffleMode()
        call.response.header("HX-Trigger", """{"poet-toast-accent":${jsonStr(NowPlayingViews.shuffleTitle(mode))}}""")
        html(NowPlayingViews.shuffleButton(mode))
    }
    post("/api/player/repeat") {
        val mode = PlayerController.advanceRepeatMode()
        call.response.header("HX-Trigger", """{"poet-toast-accent":${jsonStr(NowPlayingViews.repeatTitle(mode))}}""")
        html(NowPlayingViews.repeatButton(mode))
    }
    post("/api/player/speed") { PlayerController.cycleSpeed(); noContent() }

    post("/api/player/seek") {
        val pos = call.receiveParameters()["pos"]?.toLongOrNull()
            ?: call.request.queryParameters["pos"]?.toLongOrNull() ?: 0
        PlayerController.seekTo(pos)
        noContent()
    }

    post("/api/player/sleep") {
        val min = call.request.queryParameters["min"]?.toIntOrNull() ?: 0
        PlayerController.setSleepTimer(min)
        toastAccent(if (min > 0) "Sleep timer set: $min min" else "Sleep timer off")
    }

    post("/api/player/sleep-songs") {
        val n = call.request.queryParameters["n"]?.toIntOrNull()?.coerceIn(0, 99) ?: 0
        PlayerController.setSleepSongs(n)
        toastAccent(if (n > 0) "Sleeping after $n ${if (n == 1) "song" else "songs"}" else "Sleep timer off")
    }

    get("/api/player/lyrics") {
        val track = currentTrack(db)
        val lines = track?.lrcUri?.let { LrcParser.parse(app, it) } ?: emptyList()
        html(NowPlayingViews.lyricsDeckHtml(lines))
    }
}
