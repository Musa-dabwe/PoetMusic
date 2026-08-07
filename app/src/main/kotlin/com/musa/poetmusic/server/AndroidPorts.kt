package com.musa.poetmusic.server

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.musa.poetmusic.BuildConfig
import com.musa.poetmusic.data.BatchTagForm
import com.musa.poetmusic.data.BatchTagResult
import com.musa.poetmusic.data.FileExtras
import com.musa.poetmusic.data.HostPort
import com.musa.poetmusic.data.JournalPortrait
import com.musa.poetmusic.data.LibraryScanner
import com.musa.poetmusic.data.LibraryWatcher
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.data.ScanPort
import com.musa.poetmusic.data.TagEditor
import com.musa.poetmusic.data.TagForm
import com.musa.poetmusic.data.TagPort
import com.musa.poetmusic.data.TagResult
import com.musa.poetmusic.data.Track
import com.musa.poetmusic.playback.AudioFx
import com.musa.poetmusic.playback.EqBand
import com.musa.poetmusic.playback.EqPort
import com.musa.poetmusic.playback.EqState
import com.musa.poetmusic.widget.WidgetRenderer
import java.io.File
import java.io.InputStream

/**
 * The Android side of the ports `:core` routes against
 * (docs/desktop-app-plan.md §2.1).
 *
 * Every one of these is a thin adapter over machinery that already existed —
 * `AudioFx`, `LibraryScanner`, `TagEditor`, SAF, `MediaMetadataRetriever`. The
 * refactor deliberately did not rewrite any of it: the phone must behave
 * exactly as it did before the desktop port, and the way to guarantee that is
 * to change the shape of the call, not the code behind it.
 */

/** [EqPort] over the platform audio effects chain. */
class AndroidEq(private val db: MusicDatabase) : EqPort {

    override fun state(): EqState {
        val s = AudioFx.state(db)
        return EqState(
            available = s.available,
            enabled = s.enabled,
            preset = s.preset,
            bands = s.bands.map { EqBand(it.index, it.centerHz, it.levelMillibels) },
            minLevel = s.minLevel,
            maxLevel = s.maxLevel,
            bassAvailable = s.bassAvailable,
            bassStrength = s.bassStrength,
            virtualizerAvailable = s.virtualizerAvailable,
            virtualizerStrength = s.virtualizerStrength
        )
    }

    override fun setEnabled(on: Boolean) = AudioFx.setEnabled(db, on)
    override fun applyPreset(name: String) = AudioFx.applyPreset(db, name)
    override fun setBand(index: Int, millibels: Int) = AudioFx.setBand(db, index, millibels)
    // AudioFx returns the synchronized block's value; the port returns Unit.
    override fun setBassStrength(strength: Int) { AudioFx.setBassStrength(db, strength) }
    override fun setVirtualizerStrength(strength: Int) { AudioFx.setVirtualizerStrength(db, strength) }
}

/** [ScanPort] over the SAF document-tree scanner and its foreground watchdog. */
class AndroidScanner(context: Context, private val db: MusicDatabase) : ScanPort {

    private val app = context.applicationContext

    override val isScanning: Boolean get() = LibraryScanner.isScanning
    override val progressText: String get() = LibraryScanner.progressText

    override fun autoScanEnabled(): Boolean = LibraryScanner.autoScanEnabled(db)

    override fun setAutoScan(on: Boolean) {
        db.setSetting(ScanPort.KEY_AUTO, if (on) "1" else "0")
        if (on) LibraryWatcher.restart(app, db) else LibraryWatcher.stop()
    }

    override fun intervalHours(): Int = LibraryScanner.intervalHours(db)

    override fun setIntervalHours(hours: Int) {
        db.setSetting(ScanPort.KEY_INTERVAL_H, hours.toString())
    }

    override fun startScan() = LibraryScanner.startScan(app, db)
}

/** [TagPort] over the mp3agic-backed ID3 writer. MP3 only, as it always was. */
class AndroidTags(context: Context, private val db: MusicDatabase) : TagPort {

    private val app = context.applicationContext

    override var pendingArt: ByteArray?
        get() = TagEditor.pendingArt
        set(value) { TagEditor.pendingArt = value }

    override var pendingArtMime: String
        get() = TagEditor.pendingArtMime
        set(value) { TagEditor.pendingArtMime = value }

    override fun clearPendingArt() = TagEditor.clearPendingArt()

    override fun writesToFile(track: Track): Boolean =
        track.displayName.endsWith(".mp3", ignoreCase = true)

    override fun readFileExtras(track: Track): FileExtras {
        val extras = TagEditor.readFileExtras(app, track)
        return FileExtras(extras.comment, extras.lyrics)
    }

    override fun saveTags(trackId: Long, form: TagForm): TagResult {
        val result = TagEditor.saveTags(app, db, trackId, form.toAndroid())
        return TagResult(result.ok, result.message, result.artChanged)
    }

    override fun applyBatch(ids: List<Long>, form: BatchTagForm): BatchTagResult {
        val result = TagEditor.applyBatch(
            app, db, ids,
            TagEditor.BatchForm(
                title = form.title, artist = form.artist, album = form.album,
                albumArtist = form.albumArtist, genre = form.genre, year = form.year,
                trackNo = form.trackNo
            )
        )
        return BatchTagResult(result.written, result.libraryOnly, result.failed)
    }

    override fun saveLrc(track: Track, lrcText: String): TagResult {
        val result = TagEditor.saveLrc(app, db, track, lrcText)
        return TagResult(result.ok, result.message, result.artChanged)
    }

    private fun TagForm.toAndroid() = TagEditor.Form(
        title = title, artist = artist, album = album, albumArtist = albumArtist,
        genre = genre, year = year, trackNo = trackNo, discNo = discNo,
        composer = composer, comment = comment, lyrics = lyrics,
        artAction = artAction, rename = rename, renamePattern = renamePattern
    )
}

/**
 * [HostPort] over SAF, the asset manager and the home screen widget.
 *
 * The `*Requester` hooks are set by `MainActivity` when it resumes and cleared
 * when it is destroyed — a null one means the Activity is gone, and the routes
 * answer with a toast rather than a crash. That behaviour predates the port;
 * only the place the fields live has moved.
 */
class AndroidHost(context: Context, private val db: MusicDatabase) : HostPort {

    private val app = context.applicationContext

    @Volatile override var addFolderRequester: (() -> Unit)? = null
    @Volatile override var pinWidgetRequester: (() -> Unit)? = null
    @Volatile override var pickArtRequester: (() -> Unit)? = null
    @Volatile override var pickPortraitRequester: (() -> Unit)? = null
    @Volatile override var shareRequester: ((List<Long>) -> Unit)? = null
    @Volatile override var saveArtRequester: ((Long) -> Unit)? = null

    override fun asset(name: String): ByteArray? =
        runCatching { app.assets.open("web/$name").use { it.readBytes() } }.getOrNull()

    override fun embeddedArt(track: Track): ByteArray? {
        // Library-side art override: covers non-MP3 formats whose containers
        // can't be written, and art the user removed from a writable file.
        val overrideFile = File(TagEditor.artOverrideDir(app), "${track.id}")
        if (overrideFile.exists()) return overrideFile.readBytes().takeIf { it.isNotEmpty() }

        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(app, Uri.parse(track.uri))
            mmr.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    override fun openAudioStream(track: Track): InputStream? =
        runCatching { app.contentResolver.openInputStream(Uri.parse(track.uri)) }.getOrNull()

    override fun fileSize(track: Track): Long = try {
        app.contentResolver.query(Uri.parse(track.uri), arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    override fun deleteFile(track: Track): Boolean = try {
        DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(track.uri))
    } catch (e: Exception) {
        false
    }

    override fun readText(uri: String): String? = runCatching {
        app.contentResolver.openInputStream(Uri.parse(uri))?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()

    override fun onLibraryChanged() = WidgetRenderer.pushUpdate(app)

    override fun portraitExists(): Boolean = JournalPortrait.exists(app)
    override fun portraitBytes(): ByteArray? = JournalPortrait.read(app)
    override fun portraitMime(): String = JournalPortrait.mime(db)
    override fun portraitStamp(): String = JournalPortrait.stamp(db)
}

/** What the About screen says about *this* build. */
val ANDROID_ABOUT = AboutSpec(
    versionName = BuildConfig.VERSION_NAME,
    tagline = "offline-first, pastel-themed music player for Android.",
    overview = "Poet Music is an offline-first music player. The UI is an [htmx](https://htmx.org) " +
        "single-page app served by an embedded [Ktor](https://ktor.io) server running inside the app " +
        "process and rendered in a native WebView. Playback is handled natively by Media3 / ExoPlayer " +
        "through a foreground `MediaSessionService`, so music keeps playing with the screen off and " +
        "shows lockscreen / notification controls.",
    techStack = listOf(
        "Language" to "Kotlin",
        "UI" to "htmx single-page app in a native WebView",
        "Server" to "embedded Ktor (CIO) bound to `127.0.0.1:8080`",
        "Playback" to "Media3 / ExoPlayer in a foreground `MediaSessionService`",
        "Storage" to "SQLite (tracks, folders, playlists, settings)",
        "Minimum Android" to "8.0 (API 26)"
    ),
    bindAddress = "127.0.0.1:8080",
    architecture = listOf(
        "core/" to "the shared view layer, routes and platform interfaces — identical on Android and Linux.",
        "server/" to "the Android implementations of those interfaces.",
        "playback/" to "the foreground Media3 session and a thread-safe player bridge.",
        "data/" to "the SQLite database, library scanner and MP3 tag editor."
    ),
    storageNote = "folders are accessed through the Storage Access Framework with user-granted permissions only."
)
