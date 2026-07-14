package com.musa.poetmusic.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.musa.poetmusic.PoetApp

/**
 * Foreground Media3 service. Owns the ExoPlayer instance so audio keeps
 * playing when the WebView activity is minimized or the screen locks, and
 * publishes lockscreen / notification media controls via MediaSession.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            // Hold a partial wake lock while playing: without it Doze parks
            // the CPU shortly after the app is minimized or the screen turns
            // off, and local playback cuts out mid-song.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        mediaSession = MediaSession.Builder(this, player).build()
        PlayerController.attach(player)
        // Bring back the previous session's queue, position and playback modes.
        (application as? PoetApp)?.let { PlayerController.restoreState(it.db) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        PlayerController.detach()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
