package com.musa.poetmusic.desktop

import com.musa.poetmusic.data.LibraryStore
import com.musa.poetmusic.data.Track
import com.musa.poetmusic.playback.PlayModes
import com.musa.poetmusic.playback.PlayerSnapshot
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `org.mpris.MediaPlayer2` — the desktop's answer to the phone's MediaSession
 * (docs/desktop-app-plan.md §6).
 *
 * MPRIS is the one thing every Linux desktop agrees on for "there is a media
 * player running here": GNOME's panel and lock screen, KDE's applet and the
 * XF86Audio keys on a keyboard all read it, and none of them need to know
 * anything else about the app. Exporting it is what puts the previous /
 * play-pause / next buttons in the shell alongside the other players.
 *
 * The whole surface is two interfaces on one object at `/org/mpris/MediaPlayer2`,
 * plus `org.freedesktop.DBus.Properties`, which is where all the state actually
 * lives. Method names are capitalised because they are the wire names from the
 * spec, not Kotlin's.
 *
 * Nothing here is load-bearing: [start] returns false when there is no session
 * bus — a headless box, an ssh session, a container — and the app runs on
 * exactly as before. That mirrors how [ui.WebKitWindow] treats a missing GTK.
 *
 * Threading: D-Bus calls arrive on dbus-java's own threads and go straight to
 * [GstPlayer], which serialises everything onto its ops executor, so they are
 * no different from a Ktor worker calling in. State is published the other way
 * by polling [GstPlayer.snapshot] on a 500 ms tick and emitting
 * `PropertiesChanged` only for the properties that actually moved.
 */
class MprisService(
    private val player: GstPlayer,
    private val store: LibraryStore,
    /** Cover bytes for a track — embedded art, or the branded placeholder. */
    private val artFor: (Track) -> ByteArray?,
    private val onRaise: () -> Unit,
    private val onQuit: () -> Unit
) : MediaPlayer2, MediaPlayer2Player, Properties {

    private var connection: DBusConnection? = null

    private val ticker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "poet-mpris").apply { isDaemon = true }
    }

    /** Last set of Player properties put on the bus, for change detection. */
    private var published: Map<String, Variant<*>> = emptyMap()

    private val artFiles = ArtFiles()

    // ---------- lifecycle ----------

    /**
     * Connect, claim the bus name and export the object. False (having changed
     * nothing) when there is no session bus to talk to.
     */
    fun start(): Boolean {
        val conn = runCatching { DBusConnectionBuilder.forSessionBus().build() }.getOrElse {
            println("No D-Bus session bus — desktop media controls are unavailable.")
            return false
        }
        return runCatching {
            // A desktop is multi-user and multi-window; the spec's answer to a
            // name already taken is a per-instance suffix.
            runCatching { conn.requestBusName(BUS_NAME) }
                .getOrElse { conn.requestBusName("$BUS_NAME.instance${ProcessHandle.current().pid()}") }
            conn.exportObject(OBJECT_PATH, this)
            connection = conn
            published = playerProperties(player.snapshot)
            ticker.scheduleWithFixedDelay({ runCatching { publish() } }, 500, 500, TimeUnit.MILLISECONDS)
            true
        }.getOrElse {
            runCatching { conn.close() }
            println("Could not export MPRIS on the session bus — desktop media controls are unavailable.")
            false
        }
    }

    fun shutdown() {
        ticker.shutdownNow()
        runCatching { connection?.unExportObject(OBJECT_PATH) }
        runCatching { connection?.close() }
        connection = null
        artFiles.clear()
    }

    /**
     * Re-read state and put whatever changed on the bus. Called on the tick,
     * and directly by [DesktopHost.onLibraryChanged] so a tag edit shows up in
     * the applet without waiting for it.
     */
    fun publish() {
        val conn = connection ?: return
        val now = playerProperties(player.snapshot)
        val changed = now.filter { (key, value) -> published[key] != value }
        published = now
        if (changed.isEmpty()) return
        runCatching {
            conn.sendMessage(
                Properties.PropertiesChanged(OBJECT_PATH, PLAYER_IFACE, changed, emptyList())
            )
        }
    }

    // ---------- org.mpris.MediaPlayer2 ----------

    override fun getObjectPath(): String = OBJECT_PATH

    override fun Raise() = onRaise()

    /**
     * Deferred by a beat so dbus-java can put the empty reply on the wire
     * first. Quitting takes the process down with it, and a caller that never
     * got its reply reports a successful Quit as a connection error.
     */
    override fun Quit() {
        ticker.schedule({ onQuit() }, 150, TimeUnit.MILLISECONDS)
    }

    // ---------- org.mpris.MediaPlayer2.Player ----------

    override fun Next() = player.next()

    override fun Previous() = player.previous()

    override fun Pause() = player.pauseOnly()

    override fun PlayPause() = player.togglePlay()

    override fun Stop() = player.stop()

    override fun Play() = player.play()

    override fun Seek(offsetUs: Long) {
        player.seekBy(offsetUs / 1000)
        announceSeek()
    }

    override fun SetPosition(trackId: DBusPath, positionUs: Long) {
        // The track id guards against a stale client seeking the song that has
        // since replaced the one it was looking at.
        if (trackId.path != trackPath(player.snapshot.trackId)) return
        player.seekTo(positionUs / 1000)
        announceSeek()
    }

    /** Poet plays what is in its own library; there is nothing to open by URI. */
    override fun OpenUri(uri: String) = Unit

    /**
     * The seek landed asynchronously on the player's ops thread, so read the
     * position back after a beat rather than echoing what was asked for.
     */
    private fun announceSeek() {
        val conn = connection ?: return
        ticker.schedule({
            runCatching {
                conn.sendMessage(MediaPlayer2Player.Seeked(OBJECT_PATH, player.snapshot.positionMs * 1000))
            }
        }, 100, TimeUnit.MILLISECONDS)
    }

    // ---------- org.freedesktop.DBus.Properties ----------

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A {
        val value = when (interfaceName) {
            // Position is deliberately not in the published set: it changes
            // every frame and the spec says to poll it rather than signal it.
            PLAYER_IFACE ->
                if (propertyName == "Position") player.snapshot.positionMs * 1000
                else playerProperties(player.snapshot)[propertyName]?.value
            ROOT_IFACE -> rootProperties()[propertyName]?.value
            else -> null
        }
        return value as A
    }

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
        PLAYER_IFACE -> playerProperties(player.snapshot) +
            mapOf("Position" to Variant(player.snapshot.positionMs * 1000))
        ROOT_IFACE -> rootProperties()
        else -> emptyMap()
    }

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        if (interfaceName != PLAYER_IFACE) return
        // A writable property arrives as a variant; what is inside it is what
        // the client meant.
        val raw = (value as? Variant<*>)?.value ?: value
        when (propertyName) {
            "LoopStatus" -> player.setRepeatMode(repeatCodeOf(raw as? String ?: return))
            "Shuffle" -> player.setShuffleMode(
                if (raw as? Boolean == true) PlayModes.SHUFFLE_SONGS else PlayModes.SHUFFLE_OFF
            )
            "Rate" -> player.setSpeedNearest((raw as? Number ?: return).toFloat())
            "Volume" -> player.volume = (raw as? Number ?: return).toDouble()
        }
        publish()
    }

    // ---------- property values ----------

    private fun rootProperties(): Map<String, Variant<*>> = mapOf(
        "CanQuit" to Variant(true),
        "CanRaise" to Variant(true),
        "HasTrackList" to Variant(false),
        "Identity" to Variant("Poet Music"),
        // Ties the bus name to the installed launcher, which is how a shell
        // finds the app's icon and name for the applet. jpackage builds the
        // file name as <package>-<launcher>.desktop.
        "DesktopEntry" to Variant("poet-music-poet-music"),
        "SupportedUriSchemes" to Variant(emptyList<String>(), "as"),
        "SupportedMimeTypes" to Variant(emptyList<String>(), "as")
    )

    private fun playerProperties(s: PlayerSnapshot): Map<String, Variant<*>> = mapOf(
        "PlaybackStatus" to Variant(
            when {
                s.playing -> "Playing"
                s.hasQueue -> "Paused"
                else -> "Stopped"
            }
        ),
        "LoopStatus" to Variant(loopStatusOf(s.repeatMode)),
        "Rate" to Variant(s.speed.toDouble()),
        "Shuffle" to Variant(s.shuffleMode == PlayModes.SHUFFLE_SONGS),
        "Metadata" to Variant(metadata(s), "a{sv}"),
        "Volume" to Variant(player.volume),
        "MinimumRate" to Variant(PlayModes.SPEED_STEPS.min().toDouble()),
        "MaximumRate" to Variant(PlayModes.SPEED_STEPS.max().toDouble()),
        "CanGoNext" to Variant(s.hasQueue),
        "CanGoPrevious" to Variant(s.hasQueue),
        "CanPlay" to Variant(s.hasQueue),
        "CanPause" to Variant(s.hasQueue),
        "CanSeek" to Variant(s.hasQueue && s.durationMs > 0),
        "CanControl" to Variant(true)
    )

    /**
     * The `xesam:` fields a shell shows. Read from the library rather than from
     * the snapshot where possible, because the snapshot carries only what the
     * Now Playing bar needs.
     */
    private fun metadata(s: PlayerSnapshot): Map<String, Variant<*>> {
        val track = if (s.trackId >= 0) store.track(s.trackId) else null
        val meta = linkedMapOf<String, Variant<*>>(
            "mpris:trackid" to Variant(DBusPath(trackPath(s.trackId))),
            "mpris:length" to Variant(s.durationMs * 1000),
            "xesam:title" to Variant(s.title)
        )
        if (track == null) return meta

        meta["xesam:artist"] = Variant(listOf(track.artist), "as")
        if (track.album.isNotBlank()) meta["xesam:album"] = Variant(track.album)
        if (track.albumArtist.isNotBlank()) {
            meta["xesam:albumArtist"] = Variant(listOf(track.albumArtist), "as")
        }
        if (track.genre.isNotBlank()) meta["xesam:genre"] = Variant(listOf(track.genre), "as")
        if (track.trackNo > 0) meta["xesam:trackNumber"] = Variant(track.trackNo)
        if (track.discNo > 0) meta["xesam:discNumber"] = Variant(track.discNo)
        meta["xesam:url"] = Variant(fileUrl(track.uri))
        // Shells load the cover from a URL, so the embedded picture has to
        // become a file on disk first.
        artFiles.urlFor(track, artFor)?.let { meta["mpris:artUrl"] = Variant(fileUrl(it)) }
        return meta
    }

    private fun trackPath(trackId: Long): String =
        if (trackId < 0) NO_TRACK else "/com/musa/poetmusic/track/$trackId"

    private fun loopStatusOf(repeatMode: Int): String = when (repeatMode) {
        PlayModes.REPEAT_ONE -> "Track"
        PlayModes.REPEAT_ALL -> "Playlist"
        else -> "None"
    }

    private fun repeatCodeOf(loopStatus: String): Int = when (loopStatus) {
        "Track" -> PlayModes.REPEAT_ONE
        "Playlist" -> PlayModes.REPEAT_ALL
        else -> PlayModes.REPEAT_ONE_STOP
    }

    private companion object {
        const val BUS_NAME = "org.mpris.MediaPlayer2.poetmusic"
        const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
        const val ROOT_IFACE = "org.mpris.MediaPlayer2"
        const val PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"

        /** The spec's reserved path for "no track", which must not be reused. */
        const val NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack"
    }
}

/**
 * `file:/home/…` to `file:///home/…`.
 *
 * Java writes file URIs with the authority elided, which is legal and which
 * most readers cope with, but the three-slash form is what every other MPRIS
 * player puts on the bus and it costs nothing to match them.
 */
private fun fileUrl(uri: String): String =
    if (uri.startsWith("file:/") && !uri.startsWith("file://")) "file://" + uri.removePrefix("file:")
    else uri

/**
 * Cover art staged as files under the XDG cache directory, because MPRIS hands
 * a shell a URL and not bytes.
 *
 * Only the last few are kept: the URL has to keep working for as long as the
 * shell might still be fetching it, but a long listening session should not
 * leave a copy of every cover behind. The directory is emptied on startup, so
 * a previous run that was killed cannot accumulate either.
 */
private class ArtFiles {

    private val dir: File = File(
        System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home") + "/.cache"),
        "poet-music/mpris-art"
    )

    private val recent = ArrayDeque<File>()

    init {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    fun urlFor(track: Track, artFor: (Track) -> ByteArray?): String? = runCatching {
        // Untagged tracks all share the one placeholder file; a real cover is
        // keyed by the file's mtime so a tag edit produces a new URL rather
        // than a stale cached image.
        val key = if (track.hasArt) "${track.id}-${track.lastModified}" else "placeholder"
        existing(key)?.let { return@runCatching it.toURI().toString() }

        val bytes = artFor(track) ?: return null
        // Loaders sniff the content, but a truthful extension is what a file
        // manager or a thumbnailer looks at.
        val file = File(dir, "$key.${if (isPng(bytes)) "png" else "jpg"}")
        dir.mkdirs()
        file.writeBytes(bytes)
        if (key != "placeholder") remember(file)
        file.toURI().toString()
    }.getOrNull()

    private fun existing(key: String): File? =
        listOf("jpg", "png").map { File(dir, "$key.$it") }.firstOrNull { it.isFile }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 && bytes[0] == 0x89.toByte() &&
            bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()

    private fun remember(file: File) {
        synchronized(recent) {
            recent.addLast(file)
            while (recent.size > KEEP) recent.removeFirst().delete()
        }
    }

    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        synchronized(recent) { recent.clear() }
    }

    private companion object {
        const val KEEP = 4
    }
}

/**
 * The root interface: what the app is, and the two things a shell can do to the
 * window itself.
 */
@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

/**
 * The transport controls. Everything readable — what is playing, whether it is
 * playing, how loud — is a D-Bus property and arrives through
 * [Properties.GetAll] instead.
 */
@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offsetUs: Long)
    fun SetPosition(trackId: DBusPath, positionUs: Long)
    fun OpenUri(uri: String)

    /**
     * Position moved other than by playing. Nested inside the interface because
     * that is how dbus-java works out which interface a signal belongs to.
     */
    class Seeked(objectPath: String, positionUs: Long) : DBusSignal(objectPath, positionUs)
}
