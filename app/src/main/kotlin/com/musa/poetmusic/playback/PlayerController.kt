package com.musa.poetmusic.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.data.Track
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bridge between the Ktor server threads and the ExoPlayer instance owned by
 * PlaybackService. All player commands are posted to the main thread; server
 * threads read a periodically refreshed immutable snapshot.
 *
 * Queue model (Musicolet-style): the play queue is always a static, literal
 * sequence of media items — ExoPlayer's shuffle mode is never enabled.
 * [masterIds] remembers the source listing's original order; "shuffle"
 * rewrites the queue with a Fisher-Yates randomized copy, and un-shuffling
 * restores the master order around the currently playing song. "Play next"
 * inserts directly after the current item.
 *
 * Repeat modes exposed to the UI:
 *   0 = off (play queue through, then stop)
 *   1 = repeat one song
 *   2 = repeat playlist
 *   3 = play single song and stop
 */
object PlayerController {

    const val REPEAT_OFF = 0
    const val REPEAT_ONE = 1
    const val REPEAT_ALL = 2
    const val REPEAT_ONE_STOP = 3

    data class Snapshot(
        val trackId: Long = -1,
        val title: String = "Nothing playing",
        val artist: String = "Tap a song to start",
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val playing: Boolean = false,
        val shuffle: Boolean = false,
        val repeatMode: Int = REPEAT_OFF,
        val speed: Float = 1f,
        val hasQueue: Boolean = false,
        val sleepRemainingMs: Long = -1
    )

    /** One entry of the live play queue, in the order it will actually play. */
    data class QueueItem(
        val index: Int,
        val trackId: Long,
        val title: String,
        val artist: String,
        val current: Boolean
    )

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var player: ExoPlayer? = null
    @Volatile var snapshot = Snapshot(); private set
    @Volatile private var store: MusicDatabase? = null

    /** Human label of the listing the queue was built from ("Playing from …"). */
    @Volatile var sourceName: String = "your library"; private set

    /** Original (unshuffled) order of the last source listing; main thread only. */
    private var masterIds: List<Long> = emptyList()

    /** True while the queue holds a shuffled sequence of the master list. */
    private var shuffleActive = false

    /**
     * Invoked on the main thread after every periodic snapshot refresh.
     * PoetApp points this at the home screen widget so it can push
     * RemoteViews updates when the track or play state actually changes.
     */
    @Volatile var onSnapshotRefreshed: ((Snapshot) -> Unit)? = null

    private var sleepDeadline: Long = -1
    private val sleepRunnable = Runnable {
        player?.pause()
        sleepDeadline = -1
    }

    private var lastPersistAt = 0L
    private var lastPersistedIds = ""
    private var lastPersistedMaster = ""

    private val refresher = object : Runnable {
        override fun run() {
            refreshSnapshot()
            onSnapshotRefreshed?.invoke(snapshot)
            persistIfDue()
            handler.postDelayed(this, 500)
        }
    }

    fun attach(p: ExoPlayer) {
        player = p
        handler.removeCallbacks(refresher)
        handler.post(refresher)
    }

    /** Give the controller a settings store so playback state survives restarts. */
    fun bindStore(db: MusicDatabase) {
        store = db
    }

    fun detach() {
        handler.removeCallbacks(refresher)
        handler.removeCallbacks(sleepRunnable)
        persistNow()
        sleepDeadline = -1
        player = null
        snapshot = Snapshot()
        masterIds = emptyList()
        shuffleActive = false
        sourceName = "your library"
    }

    private fun repeatCode(p: ExoPlayer): Int =
        if (p.pauseAtEndOfMediaItems && p.repeatMode == Player.REPEAT_MODE_OFF) REPEAT_ONE_STOP
        else when (p.repeatMode) {
            Player.REPEAT_MODE_ONE -> REPEAT_ONE
            Player.REPEAT_MODE_ALL -> REPEAT_ALL
            else -> REPEAT_OFF
        }

    private fun refreshSnapshot() {
        val p = player ?: return
        val item = p.currentMediaItem
        snapshot = Snapshot(
            trackId = item?.mediaId?.toLongOrNull() ?: -1,
            title = item?.mediaMetadata?.title?.toString() ?: "Nothing playing",
            artist = item?.mediaMetadata?.artist?.toString() ?: "Tap a song to start",
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = if (p.duration > 0) p.duration else 0,
            playing = p.isPlaying,
            shuffle = shuffleActive,
            repeatMode = repeatCode(p),
            speed = p.playbackParameters.speed,
            hasQueue = p.mediaItemCount > 0,
            sleepRemainingMs = if (sleepDeadline > 0) (sleepDeadline - System.currentTimeMillis()).coerceAtLeast(0) else -1
        )
    }

    // ---------- playback state persistence ----------

    private fun persistIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastPersistAt < 3000) return
        lastPersistAt = now
        persistNow()
    }

    /** Runs on the main thread; writes are cheap key/value rows. */
    private fun persistNow() {
        val db = store ?: return
        val p = player ?: return
        val count = p.mediaItemCount
        if (count == 0) return
        val ids = StringBuilder()
        for (i in 0 until count) {
            if (i > 0) ids.append(',')
            ids.append(p.getMediaItemAt(i).mediaId)
        }
        val csv = ids.toString()
        if (csv != lastPersistedIds) {
            lastPersistedIds = csv
            db.setSetting("pb_queue", csv)
        }
        val masterCsv = masterIds.joinToString(",")
        if (masterCsv != lastPersistedMaster) {
            lastPersistedMaster = masterCsv
            db.setSetting("pb_master", masterCsv)
        }
        db.setSetting("pb_index", p.currentMediaItemIndex.toString())
        db.setSetting("pb_pos", p.currentPosition.coerceAtLeast(0).toString())
        db.setSetting("pb_shuffle", if (shuffleActive) "1" else "0")
        db.setSetting("pb_repeat", repeatCode(p).toString())
        db.setSetting("pb_speed", p.playbackParameters.speed.toString())
        db.setSetting("pb_source", sourceName)
    }

    /** Restore the last session's queue (in its exact play order, including the
     *  active shuffle sequence), position and modes (paused). */
    fun restoreState(db: MusicDatabase) {
        bindStore(db)
        val ids = db.getSetting("pb_queue", "")
            .split(',').mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return
        val tracks = db.tracksByIds(ids)
        if (tracks.isEmpty()) return
        val index = db.getSetting("pb_index", "0").toIntOrNull()?.coerceIn(0, tracks.size - 1) ?: 0
        val pos = db.getSetting("pb_pos", "0").toLongOrNull()?.coerceAtLeast(0) ?: 0
        val shuffle = db.getSetting("pb_shuffle", "0") == "1"
        val repeat = db.getSetting("pb_repeat", "0").toIntOrNull() ?: REPEAT_OFF
        val speed = db.getSetting("pb_speed", "1.0").toFloatOrNull() ?: 1f
        val master = db.getSetting("pb_master", "").split(',').mapNotNull { it.toLongOrNull() }
        val source = db.getSetting("pb_source", "your library")
        onMain { p ->
            if (p.mediaItemCount > 0) return@onMain // something is already queued
            masterIds = master.ifEmpty { ids }
            shuffleActive = shuffle
            sourceName = source
            p.shuffleModeEnabled = false
            p.setMediaItems(tracks.map(::mediaItem), index, pos)
            applyRepeatCode(p, repeat)
            p.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
            p.prepare()
            p.playWhenReady = false
        }
    }

    // ---------- queue inspection ----------

    /**
     * The queue in play order. The queue is a static sequence (shuffle
     * rewrites it in place), so the literal media item order IS the play
     * order. Safe to call from server threads: blocks briefly on the main
     * thread, and — because the handler is FIFO — always observes the effect
     * of any queue mutation posted before it.
     */
    fun queueItems(): List<QueueItem> {
        val p = player ?: return emptyList()
        val out = ArrayList<QueueItem>()
        val latch = CountDownLatch(1)
        handler.post {
            try {
                val current = p.currentMediaItemIndex
                for (i in 0 until p.mediaItemCount) {
                    val item = p.getMediaItemAt(i)
                    out += QueueItem(
                        index = i,
                        trackId = item.mediaId.toLongOrNull() ?: -1,
                        title = item.mediaMetadata.title?.toString() ?: "Unknown",
                        artist = item.mediaMetadata.artist?.toString() ?: "",
                        current = i == current
                    )
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await(1, TimeUnit.SECONDS)
        return out
    }

    /**
     * Repoint any queued media item for [trackId] at its renamed document so
     * playback keeps working after the tag editor renames the physical file.
     * ExoPlayer media items are immutable, so each match is rebuilt in place;
     * the currently playing item is left untouched to avoid interrupting it.
     */
    fun onTrackFileRenamed(trackId: Long, newUri: String) = onMain { p ->
        val id = trackId.toString()
        val cur = p.currentMediaItemIndex
        for (i in 0 until p.mediaItemCount) {
            if (i == cur) continue
            val item = p.getMediaItemAt(i)
            if (item.mediaId != id) continue
            val rebuilt = item.buildUpon().setUri(Uri.parse(newUri)).build()
            p.replaceMediaItem(i, rebuilt)
        }
    }

    /** Jump to a specific media item index in the queue and play it. */
    fun jumpTo(index: Int) = onMain { p ->
        if (index in 0 until p.mediaItemCount) {
            p.seekTo(index, 0)
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    /** Remove one upcoming item; the currently playing item is left alone. */
    fun removeQueueItem(index: Int) = onMain { p ->
        if (index in 0 until p.mediaItemCount && index != p.currentMediaItemIndex) {
            p.removeMediaItem(index)
        }
    }

    /** Reorder an upcoming item (drag-and-drop in the queue panel). */
    fun moveQueueItem(from: Int, to: Int) = onMain { p ->
        val count = p.mediaItemCount
        if (from in 0 until count && to in 0 until count && from != p.currentMediaItemIndex) {
            p.moveMediaItem(from, to)
        }
    }

    /** Clear the queue down to just the currently playing song. */
    fun clearUpcoming() = onMain { p ->
        val cur = p.currentMediaItemIndex
        if (p.mediaItemCount == 0) return@onMain
        p.removeMediaItems(cur + 1, p.mediaItemCount)
        p.removeMediaItems(0, cur)
    }

    private fun onMain(block: (ExoPlayer) -> Unit) {
        handler.post {
            player?.let {
                block(it)
                refreshSnapshot()
            }
        }
    }

    private fun mediaItem(t: Track): MediaItem = MediaItem.Builder()
        .setUri(Uri.parse(t.uri))
        .setMediaId(t.id.toString())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setAlbumTitle(t.album)
                .setArtworkUri(Uri.parse("http://127.0.0.1:8080/api/art/${t.id}"))
                .build()
        )
        .build()

    /**
     * Replace the queue from a source listing. [tracks] is kept as the master
     * (reference) order; when [shuffled], the queue itself becomes a new
     * static Fisher-Yates randomized sequence played from its first item.
     */
    fun setQueue(
        tracks: List<Track>,
        startIndex: Int,
        shuffled: Boolean,
        source: String = "your library",
        autoplay: Boolean = true
    ) {
        if (tracks.isEmpty()) return
        onMain { p ->
            masterIds = tracks.map { it.id }
            shuffleActive = shuffled
            sourceName = source
            val ordered = if (shuffled) tracks.shuffled() else tracks
            val start = if (shuffled) 0 else startIndex.coerceIn(0, tracks.size - 1)
            p.shuffleModeEnabled = false
            p.setMediaItems(ordered.map(::mediaItem), start, 0)
            p.prepare()
            p.playWhenReady = autoplay
        }
    }

    /**
     * Shuffle ON: rewrite the queue as [current song] + a static random
     * sequence of everything else. Shuffle OFF: restore the master order
     * around the current song; play-next insertions that aren't part of the
     * master list keep their relative order at the end. Playback of the
     * current song is never interrupted.
     */
    fun toggleShuffle() = onMain { p ->
        val count = p.mediaItemCount
        if (count == 0) { shuffleActive = !shuffleActive; return@onMain }
        val cur = p.currentMediaItemIndex
        val items = (0 until count).map { p.getMediaItemAt(it) }
        if (!shuffleActive) {
            val rest = items.filterIndexed { i, _ -> i != cur }.shuffled()
            p.removeMediaItems(cur + 1, count)
            p.removeMediaItems(0, cur)
            p.addMediaItems(rest)
            shuffleActive = true
        } else {
            val rank = masterIds.mapIndexed { i, id -> id.toString() to i }.toMap()
            val target = items.sortedBy { rank[it.mediaId] ?: Int.MAX_VALUE }
            val k = target.indexOfFirst { it === items[cur] }
            p.removeMediaItems(cur + 1, count)
            p.removeMediaItems(0, cur)
            p.addMediaItems(0, target.subList(0, k))
            p.addMediaItems(target.subList(k + 1, target.size))
            shuffleActive = false
        }
    }

    fun playNext(track: Track) = onMain { p ->
        if (p.mediaItemCount == 0) {
            masterIds = listOf(track.id)
            p.setMediaItem(mediaItem(track)); p.prepare(); p.play()
        } else {
            p.addMediaItem(p.currentMediaItemIndex + 1, mediaItem(track))
        }
    }

    fun addToQueue(track: Track) = onMain { p ->
        if (p.mediaItemCount == 0) {
            masterIds = listOf(track.id)
            p.setMediaItem(mediaItem(track)); p.prepare(); p.play()
        } else {
            p.addMediaItem(mediaItem(track))
        }
    }

    fun togglePlay() = onMain { p ->
        if (p.isPlaying) p.pause()
        else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    fun next() = onMain { it.seekToNextMediaItem() }

    fun previous() = onMain { p ->
        if (p.currentPosition > 3000 || !p.hasPreviousMediaItem()) p.seekTo(0)
        else p.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) = onMain { it.seekTo(positionMs.coerceAtLeast(0)) }

    private fun applyRepeatCode(p: ExoPlayer, code: Int) {
        when (code) {
            REPEAT_ONE -> { p.repeatMode = Player.REPEAT_MODE_ONE; p.pauseAtEndOfMediaItems = false }
            REPEAT_ALL -> { p.repeatMode = Player.REPEAT_MODE_ALL; p.pauseAtEndOfMediaItems = false }
            REPEAT_ONE_STOP -> { p.repeatMode = Player.REPEAT_MODE_OFF; p.pauseAtEndOfMediaItems = true }
            else -> { p.repeatMode = Player.REPEAT_MODE_OFF; p.pauseAtEndOfMediaItems = false }
        }
    }

    /** off → repeat playlist → repeat one → play single & stop → off */
    fun cycleRepeat() = onMain { p ->
        val next = when (repeatCode(p)) {
            REPEAT_OFF -> REPEAT_ALL
            REPEAT_ALL -> REPEAT_ONE
            REPEAT_ONE -> REPEAT_ONE_STOP
            else -> REPEAT_OFF
        }
        applyRepeatCode(p, next)
    }

    fun cycleSpeed() = onMain { p ->
        val steps = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)
        val cur = p.playbackParameters.speed
        val idx = steps.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f }
        p.setPlaybackSpeed(steps[(idx + 1).mod(steps.size)])
    }

    /** minutes <= 0 cancels the timer. */
    fun setSleepTimer(minutes: Int) {
        handler.post {
            handler.removeCallbacks(sleepRunnable)
            if (minutes > 0) {
                sleepDeadline = System.currentTimeMillis() + minutes * 60_000L
                handler.postDelayed(sleepRunnable, minutes * 60_000L)
            } else {
                sleepDeadline = -1
            }
            refreshSnapshot()
        }
    }
}
