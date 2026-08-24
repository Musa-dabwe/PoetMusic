package com.musa.poetmusic.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.data.Track
import com.musa.poetmusic.playback.PlayModes.DEFAULT_PREV_RESTART_MS
import com.musa.poetmusic.playback.PlayModes.PLAY_THRESHOLD_MS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bridge between the Ktor server threads and the ExoPlayer instance owned by
 * PlaybackService. All player commands are posted to the main thread; server
 * threads read a periodically refreshed immutable snapshot.
 *
 * Queue model (Musicolet-style): the play queue is always a static, literal
 * sequence of media items — ExoPlayer's shuffle mode is never enabled.
 * [masterIds] remembers the source listing's original order; shuffling
 * rewrites the queue in place, and un-shuffling restores the master order
 * around the currently playing song. "Play next" inserts directly after
 * the current item.
 *
 * Shuffle modes exposed to the UI:
 *   0 = off (play in order)
 *   1 = shuffle songs (Fisher-Yates over the whole queue)
 *
 * Repeat modes exposed to the UI:
 *   1 = repeat one song
 *   2 = repeat playlist (the base state — there is no "off")
 *   3 = play single song and stop
 */
object PlayerController : PlayerPort {

    const val REPEAT_ONE = PlayModes.REPEAT_ONE
    const val REPEAT_ALL = PlayModes.REPEAT_ALL
    const val REPEAT_ONE_STOP = PlayModes.REPEAT_ONE_STOP

    const val SHUFFLE_OFF = PlayModes.SHUFFLE_OFF
    const val SHUFFLE_SONGS = PlayModes.SHUFFLE_SONGS

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var player: ExoPlayer? = null
    @Volatile override var snapshot = PlayerSnapshot(); private set
    @Volatile private var store: MusicDatabase? = null

    /** Human label of the listing the queue was built from ("Playing from …"). */
    @Volatile override var sourceName: String = "your library"; private set

    /** Original (unshuffled) order of the last source listing; main thread only. */
    private var masterIds: List<Long> = emptyList()

    /** Active shuffle mode (SHUFFLE_*); main thread only. */
    private var shuffleMode = SHUFFLE_OFF

    /**
     * Invoked on the main thread after every periodic snapshot refresh.
     * PoetApp points this at the home screen widget so it can push
     * RemoteViews updates when the track or play state actually changes.
     */
    @Volatile var onSnapshotRefreshed: ((PlayerSnapshot) -> Unit)? = null

    /** Wall-clock instant the minute-based sleep timer expires; -1 when off. */
    private var sleepDeadline: Long = -1

    /** Songs left before the after-N-songs sleep timer pauses; 0 when off. */
    private var sleepSongs = 0

    private var lastPersistAt = 0L
    private var lastPersistedIds = ""
    private var lastPersistedMaster = ""

    /**
     * Track already written to the journal's plays log for the current listen;
     * -1 until this song crosses [PLAY_THRESHOLD_MS]. Reset on every media item
     * transition, so a repeat of the same song is logged as a second play.
     */
    private var countedTrackId = -1L


    private val refresher = object : Runnable {
        override fun run() {
            // Enforce the sleep deadline against the wall clock here rather
            // than with a postDelayed runnable: delayed messages run on
            // uptimeMillis, which stalls in deep sleep, so a timer set for
            // 30 min could fire arbitrarily late (or seemingly never).
            if (sleepDeadline > 0 && System.currentTimeMillis() >= sleepDeadline) {
                sleepDeadline = -1
                player?.pause()
            }
            refreshPlayerSnapshot()
            onSnapshotRefreshed?.invoke(snapshot)
            countPlayIfListened()
            persistIfDue()
            handler.postDelayed(this, 500)
        }
    }

    fun attach(p: ExoPlayer) {
        player = p
        // Repeat playlist is the base state: a fresh player would otherwise sit
        // in REPEAT_MODE_OFF ("play through"), a mode the UI no longer exposes.
        p.repeatMode = Player.REPEAT_MODE_ALL
        // Count song completions for the after-N-songs sleep timer. A repeat
        // of the same song counts as a song; user-initiated skips don't.
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // A new item (or a repeat of the same one) is a fresh listen.
                countedTrackId = -1
                if (sleepSongs <= 0) return
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                ) {
                    sleepSongs--
                    if (sleepSongs == 0) p.pause()
                }
            }
        })
        handler.removeCallbacks(refresher)
        handler.post(refresher)
    }

    /** Give the controller a settings store so playback state survives restarts. */
    fun bindStore(db: MusicDatabase) {
        store = db
        prevRestartMs = readPrevRestartMs(db)
    }

    fun detach() {
        handler.removeCallbacks(refresher)
        persistNow()
        sleepDeadline = -1
        sleepSongs = 0
        countedTrackId = -1
        player = null
        snapshot = PlayerSnapshot()
        masterIds = emptyList()
        shuffleMode = SHUFFLE_OFF
        sourceName = "your library"
    }

    private fun repeatCode(p: ExoPlayer): Int =
        if (p.pauseAtEndOfMediaItems && p.repeatMode == Player.REPEAT_MODE_OFF) REPEAT_ONE_STOP
        else when (p.repeatMode) {
            Player.REPEAT_MODE_ONE -> REPEAT_ONE
            else -> REPEAT_ALL
        }

    private fun refreshPlayerSnapshot() {
        val p = player ?: return
        val item = p.currentMediaItem
        snapshot = PlayerSnapshot(
            trackId = item?.mediaId?.toLongOrNull() ?: -1,
            title = item?.mediaMetadata?.title?.toString() ?: "Nothing playing",
            artist = item?.mediaMetadata?.artist?.toString() ?: "Tap a song to start",
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = if (p.duration > 0) p.duration else 0,
            playing = p.isPlaying,
            shuffleMode = shuffleMode,
            repeatMode = repeatCode(p),
            speed = p.playbackParameters.speed,
            hasQueue = p.mediaItemCount > 0,
            sleepRemainingMs = if (sleepDeadline > 0) (sleepDeadline - System.currentTimeMillis()).coerceAtLeast(0) else -1,
            sleepSongsRemaining = sleepSongs
        )
    }

    // ---------- listening journal ----------

    /**
     * Log the current song to the journal once enough of it has been heard:
     * 20 s, or half the song when it is shorter than that, so a skipped track
     * never inflates the play counts. Runs on the main thread alongside the
     * snapshot refresh; one row per listen is a cheap insert.
     */
    private fun countPlayIfListened() {
        val s = snapshot
        if (!s.playing || s.trackId < 0 || s.trackId == countedTrackId) return
        val threshold = if (s.durationMs > 0) minOf(PLAY_THRESHOLD_MS, s.durationMs / 2) else PLAY_THRESHOLD_MS
        if (s.positionMs < threshold) return
        countedTrackId = s.trackId
        store?.recordPlay(s.trackId, System.currentTimeMillis())
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
        db.setSetting("pb_shuffle", shuffleMode.toString())
        db.setSetting("pb_repeat", repeatCode(p).toString())
        db.setSetting("pb_speed", p.playbackParameters.speed.toString())
        db.setSetting("pb_source", sourceName)
    }

    /** The last persisted queue rebuilt as media items, plus its resume point. */
    data class PersistedQueue(val items: List<MediaItem>, val index: Int, val positionMs: Long)

    /**
     * Rebuild the persisted queue (in its exact play order, including the
     * active shuffle sequence). Shared by [restoreState] and the session's
     * playback-resumption callback, which must hand the system the queue on
     * demand after the process was killed.
     */
    fun persistedQueue(db: MusicDatabase): PersistedQueue? {
        val ids = db.getSetting("pb_queue", "")
            .split(',').mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return null
        val tracks = db.tracksByIds(ids)
        if (tracks.isEmpty()) return null
        val index = db.getSetting("pb_index", "0").toIntOrNull()?.coerceIn(0, tracks.size - 1) ?: 0
        val pos = db.getSetting("pb_pos", "0").toLongOrNull()?.coerceAtLeast(0) ?: 0
        return PersistedQueue(tracks.map(::mediaItem), index, pos)
    }

    /** Restore the last session's queue, position and modes (paused). */
    fun restoreState(db: MusicDatabase) {
        bindStore(db)
        val saved = persistedQueue(db) ?: return
        val shuffle = db.getSetting("pb_shuffle", "0").toIntOrNull()?.coerceIn(SHUFFLE_OFF, SHUFFLE_SONGS) ?: SHUFFLE_OFF
        val repeat = db.getSetting("pb_repeat", "$REPEAT_ALL").toIntOrNull() ?: REPEAT_ALL
        val speed = db.getSetting("pb_speed", "1.0").toFloatOrNull() ?: 1f
        val master = db.getSetting("pb_master", "").split(',').mapNotNull { it.toLongOrNull() }
        val source = db.getSetting("pb_source", "your library")
        onMain { p ->
            if (p.mediaItemCount > 0) return@onMain // something is already queued
            masterIds = master.ifEmpty { saved.items.mapNotNull { it.mediaId.toLongOrNull() } }
            shuffleMode = shuffle
            sourceName = source
            p.shuffleModeEnabled = false
            p.setMediaItems(saved.items, saved.index, saved.positionMs)
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
    override fun queueItems(): List<QueueItem> {
        val p = player ?: return emptyList()
        return onMainBlocking(emptyList()) {
            val current = p.currentMediaItemIndex
            (0 until p.mediaItemCount).map { i ->
                val item = p.getMediaItemAt(i)
                QueueItem(
                    index = i,
                    trackId = item.mediaId.toLongOrNull() ?: -1,
                    title = item.mediaMetadata.title?.toString() ?: "Unknown",
                    artist = item.mediaMetadata.artist?.toString() ?: "",
                    current = i == current
                )
            }
        }
    }

    /**
     * Repoint any queued media item for [trackId] at its renamed document so
     * playback keeps working after the tag editor renames the physical file.
     * ExoPlayer media items are immutable, so each match is rebuilt in place;
     * the currently playing item is left untouched to avoid interrupting it.
     */
    override fun onTrackFileRenamed(trackId: Long, newUri: String) = onMain { p ->
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

    override fun onTrackMetadataChanged(trackId: Long) = onMain { p ->
        val track = store?.track(trackId) ?: return@onMain
        val id = trackId.toString()
        for (i in 0 until p.mediaItemCount) {
            val item = p.getMediaItemAt(i)
            if (item.mediaId != id) continue
            val updated = item.buildUpon().setMediaMetadata(
                item.mediaMetadata.buildUpon()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .build()
            ).build()
            p.replaceMediaItem(i, updated)
        }
        refreshPlayerSnapshot()
    }

    /**
     * Purge every queue entry for tracks that were deleted from the device.
     * Iterates backwards so indices stay valid while removing; removing the
     * currently playing item makes ExoPlayer advance to the next song (or end
     * playback when nothing is left). The master order forgets them too, so
     * un-shuffling can't resurrect a deleted file.
     */
    override fun removeTracksFromQueue(trackIds: Collection<Long>) {
        if (trackIds.isEmpty()) return
        val idSet = trackIds.toHashSet()
        val mediaIds = trackIds.mapTo(HashSet()) { it.toString() }
        onMain { p ->
            for (i in p.mediaItemCount - 1 downTo 0) {
                if (p.getMediaItemAt(i).mediaId in mediaIds) p.removeMediaItem(i)
            }
            masterIds = masterIds.filterNot { it in idSet }
        }
    }

    /** Jump to a specific media item index in the queue and play it. */
    override fun jumpTo(index: Int) = onMain { p ->
        if (index in 0 until p.mediaItemCount) {
            p.seekTo(index, 0)
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    /** Remove one upcoming item; the currently playing item is left alone. */
    override fun removeQueueItem(index: Int) = onMain { p ->
        if (index in 0 until p.mediaItemCount && index != p.currentMediaItemIndex) {
            p.removeMediaItem(index)
        }
    }

    /** Reorder an upcoming item (drag-and-drop in the queue panel). */
    override fun moveQueueItem(from: Int, to: Int) = onMain { p ->
        val count = p.mediaItemCount
        if (from in 0 until count && to in 0 until count && from != p.currentMediaItemIndex) {
            p.moveMediaItem(from, to)
        }
    }

    /** Clear the queue down to just the currently playing song. */
    override fun clearUpcoming() = onMain { p ->
        val cur = p.currentMediaItemIndex
        if (p.mediaItemCount == 0) return@onMain
        p.removeMediaItems(cur + 1, p.mediaItemCount)
        p.removeMediaItems(0, cur)
    }

    private fun onMain(block: (ExoPlayer) -> Unit) {
        handler.post {
            player?.let {
                block(it)
                refreshPlayerSnapshot()
            }
        }
    }

    /**
     * Run [block] on the main thread and block the calling (server) thread
     * until it completes, up to a 1 s deadline. The handler is FIFO, so the
     * block always observes the effect of any mutation posted before it. A
     * missed deadline is logged and [fallback] returned, so a stalled main
     * thread can never hang a Ktor worker.
     */
    private fun <T> onMainBlocking(fallback: T, block: () -> T): T {
        var result = fallback
        val latch = CountDownLatch(1)
        handler.post {
            try {
                result = block()
            } finally {
                latch.countDown()
            }
        }
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                Log.w("PlayerController", "onMainBlocking timed out; returning fallback state")
                return fallback
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return fallback
        }
        return result
    }

    private fun mediaItem(t: Track): MediaItem = MediaItem.Builder()
        .setUri(Uri.parse(t.uri))
        .setMediaId(t.id.toString())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setAlbumTitle(t.album)
                .setAlbumArtist(t.albumArtist)
                .setArtworkUri(Uri.parse("http://127.0.0.1:8080/api/art/${t.id}"))
                .build()
        )
        .build()

    /**
     * Replace the queue from a source listing. [tracks] is kept as the master
     * (reference) order; when [shuffled], the queue itself becomes a new
     * static Fisher-Yates randomized sequence played from its first item.
     */
    override fun setQueue(
        tracks: List<Track>,
        startIndex: Int,
        shuffled: Boolean,
        source: String,
        autoplay: Boolean
    ) {
        if (tracks.isEmpty()) return
        onMain { p ->
            masterIds = tracks.map { it.id }
            shuffleMode = if (shuffled) SHUFFLE_SONGS else SHUFFLE_OFF
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
     * Advance shuffle to its next mode and return the committed mode. The
     * read → compute → apply happens in one serialized main-thread hop, so
     * two rapid taps can never read the same stale mode and collapse two
     * transitions into one. Safe to call from server threads (blocks via
     * [onMainBlocking], same as [queueItems]).
     */
    override fun advanceShuffleMode(): Int = onMainBlocking(snapshot.shuffleMode) {
        player?.let { p ->
            applyShuffleMode(p, (shuffleMode + 1).mod(2))
            refreshPlayerSnapshot()
            shuffleMode
        } ?: snapshot.shuffleMode
    }

    /**
     * Rewrite the queue for [mode] without interrupting the current song:
     *   SHUFFLE_OFF    — restore the master order around the current song;
     *                    play-next insertions that aren't part of the master
     *                    list keep their relative order at the end.
     *   SHUFFLE_SONGS  — [current song] + a static random sequence of
     *                    everything else.
         */
    private fun applyShuffleMode(p: ExoPlayer, mode: Int) {
        val target = mode.coerceIn(SHUFFLE_OFF, SHUFFLE_SONGS)
        if (target == shuffleMode) return
        val count = p.mediaItemCount
        if (count == 0) { shuffleMode = target; return }
        val cur = p.currentMediaItemIndex
        val items = (0 until count).map { p.getMediaItemAt(it) }
        val rank = masterIds.mapIndexed { i, id -> id.toString() to i }.toMap()
        p.removeMediaItems(cur + 1, count)
        p.removeMediaItems(0, cur)
        when (target) {
            SHUFFLE_SONGS -> p.addMediaItems(items.filterIndexed { i, _ -> i != cur }.shuffled())
            else -> {
                val master = items.sortedBy { rank[it.mediaId] ?: Int.MAX_VALUE }
                val k = master.indexOfFirst { it === items[cur] }
                p.addMediaItems(0, master.subList(0, k))
                p.addMediaItems(master.subList(k + 1, master.size))
            }
        }
        shuffleMode = target
    }

    override fun playNext(track: Track) = onMain { p ->
        if (p.mediaItemCount == 0) {
            masterIds = listOf(track.id)
            p.setMediaItem(mediaItem(track)); p.prepare(); p.play()
        } else {
            p.addMediaItem(p.currentMediaItemIndex + 1, mediaItem(track))
        }
    }

    override fun addToQueue(track: Track) = onMain { p ->
        if (p.mediaItemCount == 0) {
            masterIds = listOf(track.id)
            p.setMediaItem(mediaItem(track)); p.prepare(); p.play()
        } else {
            p.addMediaItem(mediaItem(track))
        }
    }

    override fun togglePlay() = onMain { p ->
        if (p.isPlaying) p.pause()
        else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    override fun next() = onMain { it.seekToNextMediaItem() }

    /**
     * Previous restarts the current song once [prevRestartMs] of it has played,
     * and steps back before that. 0 means never restart — Previous always goes
     * to the previous song. Cached in memory so the main thread doesn't read
     * the settings table on every tap.
     */
    @Volatile override var prevRestartMs: Long = DEFAULT_PREV_RESTART_MS; private set

    /** The persisted threshold, clamped to the range the Settings card offers. */
    fun readPrevRestartMs(db: MusicDatabase): Long =
        db.getSetting("prev_restart_ms", DEFAULT_PREV_RESTART_MS.toString())
            .toLongOrNull()?.coerceIn(0, 30_000) ?: DEFAULT_PREV_RESTART_MS

    override fun setPrevRestartMs(ms: Long) {
        val v = ms.coerceIn(0, 30_000)
        store?.setSetting("prev_restart_ms", v.toString())
        prevRestartMs = v
    }

    override fun previous() = onMain { p ->
        val threshold = prevRestartMs
        val restart = threshold > 0 && p.currentPosition > threshold
        if (restart || !p.hasPreviousMediaItem()) p.seekTo(0)
        else p.seekToPreviousMediaItem()
    }

    override fun seekTo(positionMs: Long) = onMain { it.seekTo(positionMs.coerceAtLeast(0)) }

    private fun applyRepeatCode(p: ExoPlayer, code: Int) {
        when (code) {
            REPEAT_ONE -> { p.repeatMode = Player.REPEAT_MODE_ONE; p.pauseAtEndOfMediaItems = false }
            REPEAT_ONE_STOP -> { p.repeatMode = Player.REPEAT_MODE_OFF; p.pauseAtEndOfMediaItems = true }
            // REPEAT_ALL, plus any stale persisted "off" code from before the
            // play-queue-through state was removed.
            else -> { p.repeatMode = Player.REPEAT_MODE_ALL; p.pauseAtEndOfMediaItems = false }
        }
    }

    /** Advance repeat to its next mode and return the committed mode; same
     *  serialized read-modify-write as [advanceShuffleMode]. */
    override fun advanceRepeatMode(): Int = onMainBlocking(snapshot.repeatMode) {
        player?.let { p ->
            applyRepeatCode(p, nextRepeatCode(repeatCode(p)))
            refreshPlayerSnapshot()
            snapshot.repeatMode
        } ?: snapshot.repeatMode
    }

    /** repeat playlist → repeat one → play single & stop → repeat playlist */
    private fun nextRepeatCode(code: Int): Int = PlayModes.nextRepeatCode(code)

    override fun cycleSpeed() = onMain { p ->
        val steps = PlayModes.SPEED_STEPS
        val cur = p.playbackParameters.speed
        val idx = steps.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f }
        p.setPlaybackSpeed(steps[(idx + 1).mod(steps.size)])
    }

    /** minutes <= 0 cancels every sleep timer (minute- and song-based). */
    override fun setSleepTimer(minutes: Int) {
        handler.post {
            sleepSongs = 0
            sleepDeadline = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else -1
            refreshPlayerSnapshot()
        }
    }

    /** Pause after [count] more songs finish playing; <= 0 cancels. */
    override fun setSleepSongs(count: Int) {
        handler.post {
            sleepDeadline = -1
            sleepSongs = count.coerceAtLeast(0)
            refreshPlayerSnapshot()
        }
    }
}
