package com.musa.poetmusic.desktop

import com.musa.poetmusic.data.LibraryStore
import com.musa.poetmusic.data.Track
import com.musa.poetmusic.playback.PlayModes
import com.musa.poetmusic.playback.PlayerPort
import com.musa.poetmusic.playback.PlayerSnapshot
import com.musa.poetmusic.playback.QueueItem
import org.freedesktop.gstreamer.Bin
import org.freedesktop.gstreamer.Element
import org.freedesktop.gstreamer.ElementFactory
import org.freedesktop.gstreamer.Format
import org.freedesktop.gstreamer.GhostPad
import org.freedesktop.gstreamer.Gst
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.elements.PlayBin
import org.freedesktop.gstreamer.event.SeekFlags
import org.freedesktop.gstreamer.event.SeekType
import java.io.File
import java.net.URI
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Desktop playback over GStreamer `playbin`
 * (docs/desktop-app-plan.md §3.2).
 *
 * The Android build gets its queue behaviour half from ExoPlayer's media-item
 * list and half from `PlayerController`; there is no equivalent list here, so
 * this owns the queue outright — which is fine, because the model was never
 * an engine feature. It is Musicolet-style and always a static literal
 * sequence: shuffling rewrites it in place around the current song, "play
 * next" inserts directly after the current item, and un-shuffling restores the
 * master order.
 *
 * Threading mirrors the phone: every mutation is posted to one single-thread
 * executor, so a `queueItems()` call submitted after a mutation observes it
 * (the executor is FIFO), and Ktor worker threads never touch the pipeline.
 * Server threads read [snapshot], refreshed every 500 ms.
 */
class GstPlayer(private val store: LibraryStore) : PlayerPort {

    private val bin: PlayBin
    private val eq: Element?
    private val pitch: Element?

    /** Serializes every pipeline and queue mutation, like the phone's main handler. */
    private val ops = Executors.newSingleThreadExecutor { r ->
        Thread(r, "poet-player").apply { isDaemon = true }
    }
    private val ticker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "poet-player-tick").apply { isDaemon = true }
    }

    @Volatile override var snapshot = PlayerSnapshot(); private set
    @Volatile override var sourceName: String = "your library"; private set
    @Volatile override var prevRestartMs: Long = PlayModes.DEFAULT_PREV_RESTART_MS; private set

    /** The live queue, in play order. Mutated only on [ops]. */
    private val queue = mutableListOf<Track>()
    private var currentIndex = -1

    /** Original (unshuffled) order of the last source listing. */
    private var masterIds: List<Long> = emptyList()

    private var shuffleMode = PlayModes.SHUFFLE_OFF
    private var repeatMode = PlayModes.REPEAT_ALL
    private var speed = 1f
    private var wantPlaying = false

    /** Wall-clock instant the minute-based sleep timer expires; -1 when off. */
    private var sleepDeadline = -1L

    /** Songs left before the after-N-songs sleep timer pauses; 0 when off. */
    private var sleepSongs = 0

    /**
     * Track already written to the journal's plays log for the current listen;
     * -1 until this song crosses the play threshold. Reset on every track
     * change, so a repeat of the same song is logged as a second play.
     */
    private var countedTrackId = -1L

    private var lastPersistAt = 0L

    init {
        Gst.init("PoetMusic")
        bin = PlayBin("poet-playbin")
        val filter = buildAudioFilter()
        eq = filter?.getElementByName(EQ_NAME)
        pitch = filter?.getElementByName(PITCH_NAME)
        if (filter != null) bin.set("audio-filter", filter)

        bin.bus.connect(org.freedesktop.gstreamer.Bus.EOS { ops.execute { onEndOfStream() } })
        // A file that will not decode must not wedge the queue on it.
        bin.bus.connect(org.freedesktop.gstreamer.Bus.ERROR { _, _, _ -> ops.execute { advance(auto = true) } })

        ticker.scheduleWithFixedDelay({ runCatching { tick() } }, 500, 500, TimeUnit.MILLISECONDS)
    }

    /**
     * `audioconvert ! equalizer-10bands ! pitch ! audioconvert`, ghosted as one
     * element for playbin's `audio-filter`.
     *
     * Both effect elements are optional: `equalizer-10bands` is in
     * gstreamer1.0-plugins-good and `pitch` in -bad, and a slimmed-down install
     * may carry neither. Missing ones simply drop out of the chain and the
     * corresponding UI reports itself unavailable — playback still works.
     */
    private fun buildAudioFilter(): Bin? = runCatching {
        val chain = listOfNotNull(
            ElementFactory.make("audioconvert", "poet-conv-in"),
            ElementFactory.make("equalizer-10bands", EQ_NAME),
            ElementFactory.make("pitch", PITCH_NAME),
            ElementFactory.make("audioconvert", "poet-conv-out")
        )
        // Without at least one convert on each end there is nothing to ghost.
        if (chain.size < 2) return@runCatching null
        val filter = Bin("poet-audio-filter")
        filter.addMany(*chain.toTypedArray())
        chain.zipWithNext().forEach { (a, b) -> a.link(b) }
        filter.addPad(GhostPad("sink", chain.first().getStaticPad("sink")))
        filter.addPad(GhostPad("src", chain.last().getStaticPad("src")))
        filter
    }.getOrNull()

    /** The equalizer element, for [GstEq]; null when the plugin is absent. */
    val equalizer: Element? get() = eq

    // ---------- periodic refresh ----------

    private fun tick() {
        // Enforce the sleep deadline against the wall clock rather than with a
        // delayed task, so a suspended laptop cannot make a 30-minute timer
        // fire arbitrarily late.
        if (sleepDeadline > 0 && System.currentTimeMillis() >= sleepDeadline) {
            sleepDeadline = -1
            ops.execute { pause() }
        }
        refreshSnapshot()
        countPlayIfListened()
        persistIfDue()
    }

    private fun refreshSnapshot() {
        val track = queue.getOrNull(currentIndex)
        val playing = runCatching { bin.state == State.PLAYING }.getOrDefault(false)
        val pos = runCatching { bin.queryPosition(TimeUnit.MILLISECONDS) }.getOrDefault(0L)
        val dur = runCatching { bin.queryDuration(TimeUnit.MILLISECONDS) }.getOrDefault(0L)
        snapshot = PlayerSnapshot(
            trackId = track?.id ?: -1,
            title = track?.title ?: "Nothing playing",
            artist = track?.artist ?: "Pick a song to start",
            positionMs = pos.coerceAtLeast(0),
            // A duration query before the pipeline prerolls answers 0 or -1;
            // the tag-read length is the honest fallback.
            durationMs = if (dur > 0) dur else (track?.durationMs ?: 0),
            playing = playing,
            shuffleMode = shuffleMode,
            repeatMode = repeatMode,
            speed = speed,
            hasQueue = queue.isNotEmpty(),
            sleepRemainingMs = if (sleepDeadline > 0)
                (sleepDeadline - System.currentTimeMillis()).coerceAtLeast(0) else -1,
            sleepSongsRemaining = sleepSongs
        )
    }

    /**
     * Log the current song to the journal once enough of it has been heard:
     * 20 s, or half the song when it is shorter, so a skipped track never
     * inflates the play counts.
     */
    private fun countPlayIfListened() {
        val s = snapshot
        if (!s.playing || s.trackId < 0 || s.trackId == countedTrackId) return
        val threshold =
            if (s.durationMs > 0) minOf(PlayModes.PLAY_THRESHOLD_MS, s.durationMs / 2)
            else PlayModes.PLAY_THRESHOLD_MS
        if (s.positionMs < threshold) return
        countedTrackId = s.trackId
        runCatching { store.recordPlay(s.trackId, System.currentTimeMillis()) }
    }

    private fun persistIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastPersistAt < 3000) return
        lastPersistAt = now
        runCatching { persistNow() }
    }

    private fun persistNow() {
        if (queue.isEmpty()) return
        store.setSetting("pb_queue", queue.joinToString(",") { it.id.toString() })
        store.setSetting("pb_master", masterIds.joinToString(","))
        store.setSetting("pb_index", currentIndex.coerceAtLeast(0).toString())
        store.setSetting("pb_pos", snapshot.positionMs.toString())
        store.setSetting("pb_shuffle", shuffleMode.toString())
        store.setSetting("pb_repeat", repeatMode.toString())
        store.setSetting("pb_speed", speed.toString())
        store.setSetting("pb_source", sourceName)
    }

    /** Restore the last session's queue, position and modes — paused. */
    fun restoreState() {
        prevRestartMs = store.getSetting("prev_restart_ms", PlayModes.DEFAULT_PREV_RESTART_MS.toString())
            .toLongOrNull()?.coerceIn(0, 30_000) ?: PlayModes.DEFAULT_PREV_RESTART_MS
        val ids = store.getSetting("pb_queue", "").split(',').mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return
        val tracks = store.tracksByIds(ids)
        if (tracks.isEmpty()) return
        val index = store.getSetting("pb_index", "0").toIntOrNull()?.coerceIn(0, tracks.size - 1) ?: 0
        val pos = store.getSetting("pb_pos", "0").toLongOrNull()?.coerceAtLeast(0) ?: 0
        ops.execute {
            queue.clear(); queue += tracks
            currentIndex = index
            masterIds = store.getSetting("pb_master", "").split(',').mapNotNull { it.toLongOrNull() }
                .ifEmpty { tracks.map { it.id } }
            shuffleMode = store.getSetting("pb_shuffle", "0").toIntOrNull()
                ?.coerceIn(PlayModes.SHUFFLE_OFF, PlayModes.SHUFFLE_SONGS) ?: PlayModes.SHUFFLE_OFF
            repeatMode = store.getSetting("pb_repeat", "${PlayModes.REPEAT_ALL}").toIntOrNull()
                ?: PlayModes.REPEAT_ALL
            speed = store.getSetting("pb_speed", "1.0").toFloatOrNull()?.coerceIn(0.25f, 4f) ?: 1f
            sourceName = store.getSetting("pb_source", "your library")
            load(tracks[index], autoplay = false, seekMs = pos)
        }
    }

    /** Persist and tear the pipeline down; called when the window closes. */
    fun shutdown() {
        runCatching { refreshSnapshot(); persistNow() }
        runCatching { bin.stop() }
        ticker.shutdownNow()
        ops.shutdownNow()
        runCatching { Gst.quit() }
    }

    // ---------- pipeline ----------

    /** Point the pipeline at [track]. Runs on [ops]. */
    private fun load(track: Track, autoplay: Boolean, seekMs: Long = 0) {
        countedTrackId = -1
        wantPlaying = autoplay
        bin.stop()
        runCatching { bin.setURI(URI(track.uri)) }
            .onFailure { runCatching { bin.setInputFile(File(track.uri)) } }
        applySpeed()
        if (autoplay) bin.play() else bin.pause()
        if (seekMs > 0) {
            // The seek only sticks once the pipeline has prerolled far enough to
            // know its duration; a short wait is cheaper than a state callback.
            runCatching { bin.getState(1, TimeUnit.SECONDS) }
            runCatching { bin.seek(seekMs, TimeUnit.MILLISECONDS) }
        }
        refreshSnapshot()
    }

    private fun applySpeed() {
        // `pitch`'s tempo property changes speed without transposing, which is
        // what ExoPlayer's setPlaybackSpeed does on the phone. Without the
        // plugin the chip still cycles, it just cannot take effect.
        runCatching { pitch?.set("tempo", speed.toDouble()) }
    }

    private fun pause() {
        wantPlaying = false
        bin.pause()
        refreshSnapshot()
    }

    /** End of stream: honour the repeat mode and the after-N-songs timer. */
    private fun onEndOfStream() {
        if (sleepSongs > 0) {
            sleepSongs--
            if (sleepSongs == 0) {
                pause()
                return
            }
        }
        when (repeatMode) {
            PlayModes.REPEAT_ONE -> queue.getOrNull(currentIndex)?.let { load(it, autoplay = true) }
            PlayModes.REPEAT_ONE_STOP -> pause()
            else -> advance(auto = true)
        }
    }

    /**
     * Step to the next song. [auto] distinguishes reaching the end of the queue
     * on its own — which stops at the end unless the queue repeats — from the
     * user pressing Next, which wraps.
     */
    private fun advance(auto: Boolean) {
        if (queue.isEmpty()) return
        val last = currentIndex >= queue.size - 1
        if (last && auto && repeatMode != PlayModes.REPEAT_ALL) {
            pause()
            return
        }
        currentIndex = if (last) 0 else currentIndex + 1
        load(queue[currentIndex], autoplay = true)
    }

    // ---------- PlayerPort ----------

    override fun queueItems(): List<QueueItem> = onOps(emptyList()) {
        queue.mapIndexed { i, t ->
            QueueItem(i, t.id, t.title, t.artist, i == currentIndex)
        }
    }

    override fun setQueue(
        tracks: List<Track>, startIndex: Int, shuffled: Boolean, source: String, autoplay: Boolean
    ) {
        if (tracks.isEmpty()) return
        ops.execute {
            masterIds = tracks.map { it.id }
            shuffleMode = if (shuffled) PlayModes.SHUFFLE_SONGS else PlayModes.SHUFFLE_OFF
            sourceName = source
            queue.clear()
            queue += if (shuffled) tracks.shuffled() else tracks
            currentIndex = if (shuffled) 0 else startIndex.coerceIn(0, queue.size - 1)
            load(queue[currentIndex], autoplay)
        }
    }

    override fun playNext(track: Track) = ops.execute {
        if (queue.isEmpty()) {
            masterIds = listOf(track.id)
            queue += track
            currentIndex = 0
            load(track, autoplay = true)
        } else {
            queue.add(currentIndex + 1, track)
        }
    }

    override fun addToQueue(track: Track) = ops.execute {
        if (queue.isEmpty()) {
            masterIds = listOf(track.id)
            queue += track
            currentIndex = 0
            load(track, autoplay = true)
        } else {
            queue += track
        }
    }

    override fun togglePlay() = ops.execute {
        if (queue.isEmpty()) return@execute
        if (bin.state == State.PLAYING) pause()
        else {
            wantPlaying = true
            bin.play()
            refreshSnapshot()
        }
    }

    override fun next() = ops.execute { advance(auto = false) }

    override fun previous() = ops.execute {
        if (queue.isEmpty()) return@execute
        val pos = runCatching { bin.queryPosition(TimeUnit.MILLISECONDS) }.getOrDefault(0L)
        val restart = prevRestartMs > 0 && pos > prevRestartMs
        if (restart || currentIndex <= 0) {
            runCatching { bin.seek(0, TimeUnit.MILLISECONDS) }
        } else {
            currentIndex--
            load(queue[currentIndex], autoplay = true)
        }
        refreshSnapshot()
    }

    override fun seekTo(positionMs: Long) = ops.execute {
        runCatching { bin.seek(positionMs.coerceAtLeast(0), TimeUnit.MILLISECONDS) }
        refreshSnapshot()
    }

    override fun advanceShuffleMode(): Int = onOps(snapshot.shuffleMode) {
        applyShuffleMode((shuffleMode + 1).mod(2))
        refreshSnapshot()
        shuffleMode
    }

    /**
     * Rewrite the queue for [target] without interrupting the current song:
     * SHUFFLE_OFF restores the master order around it (play-next insertions
     * that are not in the master list keep their relative order at the end);
     * SHUFFLE_SONGS makes everything else a fresh static random sequence.
     */
    private fun applyShuffleMode(target: Int) {
        val mode = target.coerceIn(PlayModes.SHUFFLE_OFF, PlayModes.SHUFFLE_SONGS)
        if (mode == shuffleMode) return
        shuffleMode = mode
        if (queue.isEmpty() || currentIndex < 0) return
        val current = queue[currentIndex]
        val rest = queue.filterIndexed { i, _ -> i != currentIndex }
        val reordered = if (mode == PlayModes.SHUFFLE_SONGS) {
            listOf(current) + rest.shuffled()
        } else {
            val rank = masterIds.withIndex().associate { (i, id) -> id to i }
            val ordered = (rest + current).sortedBy { rank[it.id] ?: Int.MAX_VALUE }
            ordered
        }
        queue.clear()
        queue += reordered
        currentIndex = queue.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    }

    override fun advanceRepeatMode(): Int = onOps(snapshot.repeatMode) {
        repeatMode = PlayModes.nextRepeatCode(repeatMode)
        refreshSnapshot()
        repeatMode
    }

    override fun cycleSpeed() = ops.execute {
        val steps = PlayModes.SPEED_STEPS
        val idx = steps.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f }
        speed = steps[(idx + 1).mod(steps.size)]
        applySpeed()
        refreshSnapshot()
    }

    override fun setSleepTimer(minutes: Int) {
        ops.execute {
            sleepSongs = 0
            sleepDeadline = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else -1
            refreshSnapshot()
        }
    }

    override fun setSleepSongs(count: Int) {
        ops.execute {
            sleepDeadline = -1
            sleepSongs = count.coerceAtLeast(0)
            refreshSnapshot()
        }
    }

    override fun jumpTo(index: Int) = ops.execute {
        if (index in queue.indices) {
            currentIndex = index
            load(queue[index], autoplay = true)
        }
    }

    override fun removeQueueItem(index: Int) = ops.execute {
        if (index in queue.indices && index != currentIndex) {
            queue.removeAt(index)
            if (index < currentIndex) currentIndex--
        }
    }

    override fun moveQueueItem(from: Int, to: Int) = ops.execute {
        if (from !in queue.indices || to !in queue.indices || from == currentIndex) return@execute
        val moved = queue.removeAt(from)
        queue.add(to.coerceIn(0, queue.size), moved)
        currentIndex = queue.indexOfFirst { it.id == snapshot.trackId }.coerceAtLeast(0)
    }

    override fun clearUpcoming() = ops.execute {
        val current = queue.getOrNull(currentIndex) ?: return@execute
        queue.clear()
        queue += current
        currentIndex = 0
    }

    override fun removeTracksFromQueue(trackIds: Collection<Long>) {
        if (trackIds.isEmpty()) return
        val ids = trackIds.toHashSet()
        ops.execute {
            val currentId = queue.getOrNull(currentIndex)?.id
            queue.removeAll { it.id in ids }
            // The master order forgets them too, so un-shuffling cannot
            // resurrect a deleted file.
            masterIds = masterIds.filterNot { it in ids }
            currentIndex = queue.indexOfFirst { it.id == currentId }
            if (currentIndex < 0) {
                currentIndex = if (queue.isEmpty()) -1 else 0
                if (queue.isEmpty()) bin.stop() else load(queue[0], autoplay = wantPlaying)
            }
        }
    }

    override fun onTrackFileRenamed(trackId: Long, newUri: String) = ops.execute {
        queue.forEachIndexed { i, t ->
            if (t.id == trackId && i != currentIndex) queue[i] = t.copy(uri = newUri)
        }
    }

    override fun setPrevRestartMs(ms: Long) {
        val v = ms.coerceIn(0, 30_000)
        store.setSetting("prev_restart_ms", v.toString())
        prevRestartMs = v
    }

    /**
     * Run [block] on the ops thread and block the calling (server) thread until
     * it completes, up to a 1 s deadline. The executor is FIFO, so the block
     * always observes any mutation submitted before it; a missed deadline
     * returns [fallback] so a wedged pipeline cannot hang a Ktor worker.
     */
    private fun <T> onOps(fallback: T, block: () -> T): T = try {
        ops.submit<T>(block).get(1, TimeUnit.SECONDS)
    } catch (e: Exception) {
        fallback
    }

    private companion object {
        const val EQ_NAME = "poet-eq"
        const val PITCH_NAME = "poet-pitch"
    }
}
