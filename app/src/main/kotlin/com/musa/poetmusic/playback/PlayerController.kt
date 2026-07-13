package com.musa.poetmusic.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.musa.poetmusic.data.Track

/**
 * Bridge between the Ktor server threads and the ExoPlayer instance owned by
 * PlaybackService. All player commands are posted to the main thread; server
 * threads read a periodically refreshed immutable snapshot.
 */
object PlayerController {

    data class Snapshot(
        val trackId: Long = -1,
        val title: String = "Nothing playing",
        val artist: String = "Tap a song to start",
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val playing: Boolean = false,
        val shuffle: Boolean = false,
        val repeatMode: Int = Player.REPEAT_MODE_OFF,
        val speed: Float = 1f,
        val hasQueue: Boolean = false,
        val sleepRemainingMs: Long = -1
    )

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var player: ExoPlayer? = null
    @Volatile var snapshot = Snapshot(); private set

    private var sleepDeadline: Long = -1
    private val sleepRunnable = Runnable {
        player?.pause()
        sleepDeadline = -1
    }

    private val refresher = object : Runnable {
        override fun run() {
            refreshSnapshot()
            handler.postDelayed(this, 500)
        }
    }

    fun attach(p: ExoPlayer) {
        player = p
        handler.removeCallbacks(refresher)
        handler.post(refresher)
    }

    fun detach() {
        handler.removeCallbacks(refresher)
        handler.removeCallbacks(sleepRunnable)
        sleepDeadline = -1
        player = null
        snapshot = Snapshot()
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
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
            speed = p.playbackParameters.speed,
            hasQueue = p.mediaItemCount > 0,
            sleepRemainingMs = if (sleepDeadline > 0) (sleepDeadline - System.currentTimeMillis()).coerceAtLeast(0) else -1
        )
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

    fun setQueue(tracks: List<Track>, startIndex: Int, shuffled: Boolean, autoplay: Boolean = true) {
        if (tracks.isEmpty()) return
        onMain { p ->
            p.shuffleModeEnabled = shuffled
            p.setMediaItems(tracks.map(::mediaItem), startIndex.coerceIn(0, tracks.size - 1), 0)
            p.prepare()
            p.playWhenReady = autoplay
        }
    }

    fun playNext(track: Track) = onMain { p ->
        if (p.mediaItemCount == 0) {
            p.setMediaItem(mediaItem(track)); p.prepare(); p.play()
        } else {
            p.addMediaItem(p.currentMediaItemIndex + 1, mediaItem(track))
        }
    }

    fun addToQueue(track: Track) = onMain { p ->
        if (p.mediaItemCount == 0) {
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

    fun toggleShuffle() = onMain { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = onMain { p ->
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
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
