package com.musa.poetmusic.playback

import com.musa.poetmusic.data.Track

/**
 * Playback modes, shared by every implementation and by the view layer that
 * renders the shuffle and repeat buttons.
 *
 * Shuffle: 0 = play in order, 1 = shuffle songs (a static Fisher-Yates
 * sequence — the queue is always a literal list, never an engine shuffle flag).
 *
 * Repeat: 1 = repeat one song, 2 = repeat playlist (the base state — there is
 * no "off"), 3 = play single song and stop.
 */
object PlayModes {
    const val REPEAT_ONE = 1
    const val REPEAT_ALL = 2
    const val REPEAT_ONE_STOP = 3

    const val SHUFFLE_OFF = 0
    const val SHUFFLE_SONGS = 1

    /** repeat playlist → repeat one → play single & stop → repeat playlist */
    fun nextRepeatCode(code: Int): Int = when (code) {
        REPEAT_ALL -> REPEAT_ONE
        REPEAT_ONE -> REPEAT_ONE_STOP
        else -> REPEAT_ALL
    }

    /** The speed steps the Now Playing speed chip cycles through. */
    val SPEED_STEPS = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)

    /** A song counts as played, for the journal, once this much has been heard. */
    const val PLAY_THRESHOLD_MS = 20_000L

    /** Previous restarts the song after 3 s unless Settings says otherwise. */
    const val DEFAULT_PREV_RESTART_MS = 3_000L
}

/** Immutable player state, read by server threads without touching the engine. */
data class PlayerSnapshot(
    val trackId: Long = -1,
    val title: String = "Nothing playing",
    val artist: String = "Tap a song to start",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val shuffleMode: Int = PlayModes.SHUFFLE_OFF,
    val repeatMode: Int = PlayModes.REPEAT_ALL,
    val speed: Float = 1f,
    val hasQueue: Boolean = false,
    val sleepRemainingMs: Long = -1,
    val sleepSongsRemaining: Int = 0
)

/** One entry of the live play queue, in the order it will actually play. */
data class QueueItem(
    val index: Int,
    val trackId: Long,
    val title: String,
    val artist: String,
    val current: Boolean
)

/**
 * The player, as the view layer and the routes see it
 * (docs/desktop-app-plan.md §2.1).
 *
 * Android backs this with Media3/ExoPlayer inside a foreground
 * `MediaSessionService`; the desktop with GStreamer `playbin`. The queue model
 * is Musicolet-style and belongs to the implementation, not to the engine: the
 * queue is always a static literal sequence, shuffling rewrites it in place,
 * and "play next" inserts directly after the current item.
 */
interface PlayerPort {

    /** Periodically refreshed; safe to read from any thread. */
    val snapshot: PlayerSnapshot

    /** Human label of the listing the queue was built from ("Playing from …"). */
    val sourceName: String

    /** The queue in play order. Safe to call from server threads. */
    fun queueItems(): List<QueueItem>

    /**
     * Replace the queue from a source listing. [tracks] is kept as the master
     * (reference) order; when [shuffled], the queue becomes a new static
     * randomized sequence played from its first item.
     */
    fun setQueue(
        tracks: List<Track>,
        startIndex: Int,
        shuffled: Boolean,
        source: String = "your library",
        autoplay: Boolean = true
    )

    fun playNext(track: Track)
    fun addToQueue(track: Track)

    fun togglePlay()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)

    /** Advance the mode and return what was actually committed, in one hop. */
    fun advanceShuffleMode(): Int
    fun advanceRepeatMode(): Int
    fun cycleSpeed()

    /** minutes <= 0 cancels every sleep timer (minute- and song-based). */
    fun setSleepTimer(minutes: Int)

    /** Pause after [count] more songs finish playing; <= 0 cancels. */
    fun setSleepSongs(count: Int)

    fun jumpTo(index: Int)
    fun removeQueueItem(index: Int)
    fun moveQueueItem(from: Int, to: Int)
    fun clearUpcoming()

    /** A deleted file must not linger in the play queue. */
    fun removeTracksFromQueue(trackIds: Collection<Long>)

    /** Repoint queued entries after the tag editor renames the physical file. */
    fun onTrackFileRenamed(trackId: Long, newUri: String)

    /**
     * Previous restarts the current song once this much of it has played, and
     * steps back before that. 0 means never restart.
     */
    val prevRestartMs: Long
    fun setPrevRestartMs(ms: Long)
}
