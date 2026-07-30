package com.musa.poetmusic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import com.musa.poetmusic.MainActivity
import com.musa.poetmusic.PoetApp
import com.musa.poetmusic.R
import com.musa.poetmusic.data.AppSettings
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.playback.PlaybackService
import com.musa.poetmusic.playback.PlayerController
import com.musa.poetmusic.server.Shell
import java.util.concurrent.Executors

/**
 * Renders the home screen widget from the current PlayerController snapshot
 * and the same theme settings the in-app UI uses (accent + canvas tint), so
 * a swatch tap in Settings recolors the launcher widget immediately.
 *
 * All rendering runs on a single background thread: album-art decoding may
 * touch disk, and funneling every update through one queue also serializes
 * concurrent pushes from the server, the playback service and the launcher.
 */
object WidgetRenderer {

    private const val INK = 0xFF3B3651.toInt()
    private const val MUTED = 0xFF8A84A3.toInt()
    private const val ART_SIZE = 128

    /** Height (dp) at which the widget switches from the 4x1 pill to the full card. */
    private const val FULL_MIN_HEIGHT_DP = 100

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "poet-widget") }

    // Last state pushed to the launcher; RemoteViews updates are IPC-heavy,
    // so position ticks are deliberately ignored (§ WidgetPlan 4.5).
    @Volatile private var lastTrackId = Long.MIN_VALUE
    @Volatile private var lastPlaying = false

    // Single-entry art cache: the widget only ever shows the current track.
    private var artTrackId = Long.MIN_VALUE
    private var artBitmap: Bitmap? = null

    /** Hooked into PlayerController's 500ms refresher (set up in PoetApp). */
    fun onSnapshot(context: Context, s: PlayerController.Snapshot) {
        if (s.trackId == lastTrackId && s.playing == lastPlaying) return
        lastTrackId = s.trackId
        lastPlaying = s.playing
        pushUpdate(context)
    }

    /** Re-render every placed widget instance. Safe to call from any thread. */
    fun pushUpdate(context: Context) {
        val app = context.applicationContext
        executor.execute {
            runCatching {
                val mgr = AppWidgetManager.getInstance(app) ?: return@execute
                val ids = mgr.getAppWidgetIds(ComponentName(app, PoetWidgetProvider::class.java))
                render(app, mgr, ids)
            }
        }
    }

    /** Launcher-driven updates (placement, reboot, resize) from the provider. */
    fun requestUpdate(context: Context, ids: IntArray) {
        val app = context.applicationContext
        executor.execute {
            runCatching { render(app, AppWidgetManager.getInstance(app) ?: return@execute, ids) }
        }
    }

    private fun render(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val db = (context as? PoetApp)?.db ?: (context.applicationContext as? PoetApp)?.db ?: return

        val settings = AppSettings.from(db)
        val accent = parseColor(settings.accent, 0xFFB9A5EC.toInt())
        val canvas = parseColor(
            Shell.CANVAS_TINTS[settings.theme] ?: Shell.CANVAS_TINTS.getValue(AppSettings.DEFAULT_THEME),
            0xFFF2EFFA.toInt()
        )

        val s = PlayerController.snapshot
        val fav = s.trackId >= 0 && db.track(s.trackId)?.favorite == true
        val art = artFor(context, db, s.trackId)

        for (id in ids) {
            val views = if (Build.VERSION.SDK_INT >= 31) {
                // The launcher picks the closest layout live during resizing.
                RemoteViews(
                    mapOf(
                        SizeF(250f, 40f) to fill(context, R.layout.widget_compact, false, s, fav, art, accent, canvas),
                        SizeF(250f, 110f) to fill(context, R.layout.widget_full, true, s, fav, art, accent, canvas)
                    )
                )
            } else {
                val minH = mgr.getAppWidgetOptions(id)
                    ?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
                val full = minH >= FULL_MIN_HEIGHT_DP
                fill(context, if (full) R.layout.widget_full else R.layout.widget_compact, full, s, fav, art, accent, canvas)
            }
            mgr.updateAppWidget(id, views)
        }
    }

    private fun fill(
        context: Context,
        layout: Int,
        full: Boolean,
        s: PlayerController.Snapshot,
        fav: Boolean,
        art: Bitmap?,
        accent: Int,
        canvas: Int
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layout)

        // Pastel theming: tint the white rounded drawables to the live theme.
        views.setInt(R.id.widget_bg, "setColorFilter", canvas)
        views.setInt(R.id.widget_play_bg, "setColorFilter", accent)
        views.setTextColor(R.id.widget_title, INK)
        views.setTextColor(R.id.widget_artist, MUTED)

        views.setTextViewText(R.id.widget_title, s.title)
        views.setTextViewText(R.id.widget_artist, s.artist)
        views.setImageViewResource(R.id.btn_play, if (s.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
        if (art != null) views.setImageViewBitmap(R.id.widget_art, art)
        else views.setImageViewResource(R.id.widget_art, R.drawable.ic_w_note)

        if (full) {
            val progress = if (s.durationMs > 0) (s.positionMs * 100 / s.durationMs).toInt() else 0
            views.setProgressBar(R.id.widget_progress, 100, progress.coerceIn(0, 100), false)
            if (Build.VERSION.SDK_INT >= 31) {
                views.setColorStateList(R.id.widget_progress, "setProgressTintList", ColorStateList.valueOf(accent))
            }
            views.setImageViewResource(R.id.btn_fav, if (fav) R.drawable.ic_w_heart else R.drawable.ic_w_heart_off)
            views.setOnClickPendingIntent(R.id.btn_prev, servicePending(context, 3, PlaybackService.ACTION_WIDGET_PREV))
            views.setOnClickPendingIntent(R.id.btn_fav, servicePending(context, 4, PlaybackService.ACTION_WIDGET_FAVOURITE))
        }

        views.setOnClickPendingIntent(R.id.btn_play, servicePending(context, 1, PlaybackService.ACTION_WIDGET_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.btn_next, servicePending(context, 2, PlaybackService.ACTION_WIDGET_NEXT))

        val open = Intent(context, MainActivity::class.java)
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )
        return views
    }

    /**
     * Commands target PlaybackService directly: service onCreate (player
     * attach + queue restore) is guaranteed to run before onStartCommand,
     * so taps work even after the process was killed. Distinct request
     * codes keep the four PendingIntents from overwriting each other.
     */
    private fun servicePending(context: Context, requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun parseColor(hex: String, fallback: Int): Int =
        runCatching { Color.parseColor(hex) }.getOrDefault(fallback)

    private fun artFor(context: Context, db: MusicDatabase, trackId: Long): Bitmap? {
        if (trackId < 0) {
            artTrackId = Long.MIN_VALUE
            artBitmap = null
            return null
        }
        if (trackId == artTrackId) return artBitmap
        val decoded = runCatching {
            val uri = db.track(trackId)?.uri ?: return@runCatching null
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, Uri.parse(uri))
                mmr.embeddedPicture?.let(::decodeScaled)
            } finally {
                runCatching { mmr.release() }
            }
        }.getOrNull()
        artTrackId = trackId
        artBitmap = decoded?.let(::roundCorners)
        return artBitmap
    }

    /** Decode embedded art down to ~ART_SIZE px; RemoteViews bitmaps must stay small. */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ART_SIZE && bounds.outHeight / (sample * 2) >= ART_SIZE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** Bake the 10dp-style rounded corners into the bitmap: RemoteViews can't clip. */
    private fun roundCorners(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height, ART_SIZE)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = size * 0.22f
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        if (scaled !== src) scaled.recycle()
        return out
    }
}
