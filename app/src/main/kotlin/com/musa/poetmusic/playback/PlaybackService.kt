package com.musa.poetmusic.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musa.poetmusic.MainActivity
import com.musa.poetmusic.PoetApp
import com.musa.poetmusic.R
import com.musa.poetmusic.widget.WidgetRenderer

/**
 * Foreground Media3 service. Owns the ExoPlayer instance so audio keeps
 * playing when the WebView activity is minimized or the screen locks, and
 * publishes lockscreen / notification media controls via MediaSession.
 *
 * Also the command sink for the home screen widget: widget buttons send
 * explicit service intents, so service creation (player attach + queue
 * restore in onCreate) is guaranteed to precede command delivery in
 * onStartCommand — taps keep working after the process was killed.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_WIDGET_PLAY_PAUSE = "com.musa.poetmusic.widget.PLAY_PAUSE"
        const val ACTION_WIDGET_NEXT = "com.musa.poetmusic.widget.NEXT"
        const val ACTION_WIDGET_PREV = "com.musa.poetmusic.widget.PREV"
        const val ACTION_WIDGET_FAVOURITE = "com.musa.poetmusic.widget.FAVOURITE"

        /** Custom session command backing the notification's favourite heart. */
        private const val CMD_FAVOURITE = "poet.FAVOURITE"
    }

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // Poet small icon + named channel; colors/layout on Android 13+ stay
        // system-rendered and are deliberately not fought (see docs/WidgetPlan.md §2).
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.playback_channel)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_poet) }
        )
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
        // Keep the notification heart in sync with the track being played.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refreshFavouriteButton()
        })
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(PoetSessionCallback())
            .setMediaButtonPreferences(listOf(favouriteButton(false)))
            // Tapping the notification body / lockscreen player opens the app.
            // MainActivity is singleTask, so this surfaces the existing task.
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        PlayerController.attach(player)
        // Bring back the previous session's queue, position and playback modes.
        (application as? PoetApp)?.let { PlayerController.restoreState(it.db) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Commands post to the main-thread handler, so they land behind the
        // restoreState runnable queued in onCreate — cold-start taps operate
        // on the restored queue, never on an empty player.
        when (intent?.action) {
            ACTION_WIDGET_PLAY_PAUSE -> PlayerController.togglePlay()
            ACTION_WIDGET_NEXT -> PlayerController.next()
            ACTION_WIDGET_PREV -> PlayerController.previous()
            ACTION_WIDGET_FAVOURITE -> toggleFavourite()
        }
        return super.onStartCommand(intent, flags, startId)
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
        // Leave the widget showing the idle state instead of a stale ⏸/▶.
        WidgetRenderer.pushUpdate(applicationContext)
        super.onDestroy()
    }

    private fun favouriteButton(fav: Boolean): CommandButton =
        CommandButton.Builder(if (fav) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (fav) "Remove from favourites" else "Add to favourites")
            .setSessionCommand(SessionCommand(CMD_FAVOURITE, Bundle.EMPTY))
            .build()

    private fun currentFavourite(): Boolean {
        val db = (application as? PoetApp)?.db ?: return false
        val id = mediaSession?.player?.currentMediaItem?.mediaId?.toLongOrNull() ?: return false
        return db.track(id)?.favorite == true
    }

    private fun refreshFavouriteButton() {
        mediaSession?.setMediaButtonPreferences(listOf(favouriteButton(currentFavourite())))
    }

    private fun toggleFavourite() {
        val db = (application as? PoetApp)?.db ?: return
        val id = PlayerController.snapshot.trackId
        if (id < 0) return
        val track = db.track(id) ?: return
        db.setFavorite(track.id, !track.favorite)
        refreshFavouriteButton()
        WidgetRenderer.pushUpdate(this)
    }

    private inner class PoetSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_FAVOURITE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_FAVOURITE) {
                toggleFavourite()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        /**
         * System playback resumption: after the process died (or the device
         * rebooted), the media resumption notification / a Bluetooth play
         * button asks us for something to play. Hand back the persisted queue
         * at its saved position. The db read is a few key/value rows — cheap
         * enough to answer inline, same as restoreState in onCreate.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val db = (application as? PoetApp)?.db
                ?: return super.onPlaybackResumption(mediaSession, controller)
            val saved = PlayerController.persistedQueue(db)
                ?: return super.onPlaybackResumption(mediaSession, controller)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(saved.items, saved.index, saved.positionMs)
            )
        }
    }
}
