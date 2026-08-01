package com.musa.poetmusic.desktop

import com.musa.poetmusic.data.AlbumRow
import com.musa.poetmusic.data.ArtistRow
import com.musa.poetmusic.data.CORE_TAG_FIELDS
import com.musa.poetmusic.data.FormatShare
import com.musa.poetmusic.data.GenreRow
import com.musa.poetmusic.data.JOURNAL_TOP_N
import com.musa.poetmusic.data.JournalStats
import com.musa.poetmusic.data.LibraryStore
import com.musa.poetmusic.data.MusicFolder
import com.musa.poetmusic.data.PeakDay
import com.musa.poetmusic.data.PeakHour
import com.musa.poetmusic.data.Playlist
import com.musa.poetmusic.data.TopAlbum
import com.musa.poetmusic.data.TopArtist
import com.musa.poetmusic.data.TopGenre
import com.musa.poetmusic.data.TopTrack
import com.musa.poetmusic.data.Track
import com.musa.poetmusic.data.UNKNOWN_ALBUM
import com.musa.poetmusic.data.UNKNOWN_ARTIST
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * The desktop library store: the same tables and the same SQL the Android
 * build keeps in `android.database.sqlite`, over `sqlite-jdbc`
 * (docs/desktop-app-plan.md §3.1).
 *
 * The schema is column-for-column identical to `MusicDatabase`'s, and the
 * queries are ported rather than rewritten, so both stores answer a given
 * `LibraryStore` call the same way — the shared view layer above has no way to
 * tell them apart, and a library file could be moved between the two.
 *
 * One connection, guarded by a lock and a busy timeout. The phone needs a WAL
 * connection pool because a background scan and the WebView's request storm
 * genuinely overlap; here a scan is the only writer and serializing is both
 * simpler and enough.
 */
class DesktopLibrary(dbFile: File) : LibraryStore {

    private val conn: Connection
    private val lock = Any()

    init {
        dbFile.parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.execute("PRAGMA foreign_keys=ON")
        }
        createSchema()
    }

    private fun createSchema() = withDb { c ->
        c.createStatement().use { st ->
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS tracks(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uri TEXT UNIQUE NOT NULL,
                    parent_uri TEXT NOT NULL DEFAULT '',
                    display_name TEXT NOT NULL DEFAULT '',
                    title TEXT NOT NULL DEFAULT '',
                    artist TEXT NOT NULL DEFAULT '$UNKNOWN_ARTIST',
                    album TEXT NOT NULL DEFAULT '$UNKNOWN_ALBUM',
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    track_no INTEGER NOT NULL DEFAULT 0,
                    genre TEXT NOT NULL DEFAULT '',
                    year TEXT NOT NULL DEFAULT '',
                    album_artist TEXT NOT NULL DEFAULT '',
                    disc_no INTEGER NOT NULL DEFAULT 0,
                    composer TEXT NOT NULL DEFAULT '',
                    comment TEXT NOT NULL DEFAULT '',
                    has_art INTEGER NOT NULL DEFAULT 0,
                    lrc_uri TEXT,
                    folder_id INTEGER NOT NULL DEFAULT 0,
                    favorite INTEGER NOT NULL DEFAULT 0,
                    date_added INTEGER NOT NULL DEFAULT 0,
                    last_modified INTEGER NOT NULL DEFAULT 0
                )"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS folders(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    tree_uri TEXT UNIQUE NOT NULL,
                    display_path TEXT NOT NULL
                )"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS playlists(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS playlist_tracks(
                    playlist_id INTEGER NOT NULL,
                    track_id INTEGER NOT NULL,
                    position INTEGER NOT NULL,
                    PRIMARY KEY(playlist_id, track_id)
                )"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS settings(
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS plays(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    track_id INTEGER NOT NULL,
                    played_at INTEGER NOT NULL
                )"""
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_plays_track ON plays(track_id)")
        }
        migrateInterimSettingsTable(c)
    }

    /**
     * The interim desktop prototype (before the view layer was shared) wrote
     * `settings(k, v)`; Android has always used `settings(key, value)`, and the
     * shared store follows Android. Rebuild the table and carry the rows over
     * so an early install keeps its accent, theme and scan preferences.
     */
    private fun migrateInterimSettingsTable(c: Connection) {
        val columns = c.query("PRAGMA table_info(settings)") { it.getString("name") }
        if (!columns.contains("k")) return
        c.createStatement().use { st ->
            st.executeUpdate("ALTER TABLE settings RENAME TO settings_interim")
            st.executeUpdate("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            st.executeUpdate("INSERT OR IGNORE INTO settings(key, value) SELECT k, v FROM settings_interim")
            st.executeUpdate("DROP TABLE settings_interim")
        }
    }

    private inline fun <T> withDb(block: (Connection) -> T): T = synchronized(lock) { block(conn) }

    /** Runs [block] in a transaction, rolling back if it throws. */
    private inline fun <T> transaction(crossinline block: (Connection) -> T): T = withDb { c ->
        val previous = c.autoCommit
        c.autoCommit = false
        try {
            val result = block(c)
            c.commit()
            result
        } catch (e: Exception) {
            runCatching { c.rollback() }
            throw e
        } finally {
            c.autoCommit = previous
        }
    }

    private fun Connection.update(sql: String, vararg args: Any?): Int =
        prepareStatement(sql).use { ps -> ps.bind(*args); ps.executeUpdate() }

    private fun java.sql.PreparedStatement.bind(vararg args: Any?) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> setObject(i + 1, null)
                is Long -> setLong(i + 1, a)
                is Int -> setInt(i + 1, a)
                is Boolean -> setInt(i + 1, if (a) 1 else 0)
                else -> setString(i + 1, a.toString())
            }
        }
    }

    private fun <T> Connection.query(sql: String, vararg args: Any?, read: (ResultSet) -> T): List<T> =
        prepareStatement(sql).use { ps ->
            ps.bind(*args)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<T>()
                while (rs.next()) out += read(rs)
                out
            }
        }

    // ---------- settings ----------

    override fun getSetting(key: String, default: String): String = withDb { c ->
        c.query("SELECT value FROM settings WHERE key=?", key) { it.getString(1) }.firstOrNull() ?: default
    }

    override fun setSetting(key: String, value: String) {
        withDb { c ->
            c.update(
                "INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                key, value
            )
        }
    }

    // ---------- folders ----------

    override fun folders(): List<MusicFolder> = withDb { c ->
        c.query("SELECT id, tree_uri, display_path FROM folders ORDER BY id") {
            MusicFolder(it.getLong(1), it.getString(2), it.getString(3))
        }
    }

    override fun addFolder(treeUri: String, displayPath: String): Long = withDb { c ->
        c.update(
            "INSERT INTO folders(tree_uri, display_path) VALUES(?,?) ON CONFLICT(tree_uri) DO UPDATE SET display_path=excluded.display_path",
            treeUri, displayPath
        )
        c.query("SELECT id FROM folders WHERE tree_uri=?", treeUri) { it.getLong(1) }.firstOrNull() ?: 0L
    }

    override fun removeFolder(id: Long) {
        transaction { c ->
            c.update("DELETE FROM tracks WHERE folder_id=?", id)
            c.update("DELETE FROM folders WHERE id=?", id)
            pruneOrphanRows(c)
        }
    }

    /** Drop playlist entries and journal plays that point at tracks that are gone. */
    private fun pruneOrphanRows(c: Connection) {
        c.update("DELETE FROM playlist_tracks WHERE track_id NOT IN (SELECT id FROM tracks)")
        c.update("DELETE FROM plays WHERE track_id NOT IN (SELECT id FROM tracks)")
    }

    // ---------- tracks ----------

    private val trackCols =
        "id, uri, parent_uri, display_name, title, artist, album, duration_ms, track_no, genre, year, " +
            "has_art, lrc_uri, folder_id, favorite, date_added, last_modified, album_artist, disc_no, composer, comment"

    private fun trackFrom(c: ResultSet) = Track(
        id = c.getLong(1), uri = c.getString(2), parentUri = c.getString(3), displayName = c.getString(4),
        title = c.getString(5), artist = c.getString(6), album = c.getString(7), durationMs = c.getLong(8),
        trackNo = c.getInt(9), genre = c.getString(10), year = c.getString(11),
        hasArt = c.getInt(12) == 1, lrcUri = c.getString(13), folderId = c.getLong(14),
        favorite = c.getInt(15) == 1, dateAdded = c.getLong(16), lastModified = c.getLong(17),
        albumArtist = c.getString(18), discNo = c.getInt(19), composer = c.getString(20), comment = c.getString(21)
    )

    /**
     * Every order ends in `id` so it is a total order: LIMIT/OFFSET paging over
     * a sort whose ties SQLite may break differently per query would otherwise
     * repeat or skip rows between pages.
     */
    private fun trackOrder(sort: String): String = when (sort) {
        "title_desc" -> "title COLLATE NOCASE DESC, id"
        "artist" -> "artist COLLATE NOCASE, title COLLATE NOCASE, id"
        "artist_desc" -> "artist COLLATE NOCASE DESC, title COLLATE NOCASE, id"
        "date_modified" -> "last_modified DESC, date_added DESC, id DESC"
        "date_added" -> "date_added ASC, id ASC"
        "recent" -> "date_added DESC, id DESC"
        "duration" -> "duration_ms DESC, id"
        "duration_asc" -> "duration_ms ASC, id"
        else -> "title COLLATE NOCASE, id"
    }

    /** WHERE clause + bound arguments for the library search box. */
    private fun trackFilter(query: String): Pair<String, Array<Any?>> =
        if (query.isBlank()) "" to emptyArray()
        else "WHERE title LIKE ? OR artist LIKE ? OR album LIKE ?" to
            arrayOf<Any?>("%$query%", "%$query%", "%$query%")

    override fun tracks(query: String, sort: String, limit: Int, offset: Int): List<Track> = withDb { c ->
        val (where, args) = trackFilter(query)
        val window = if (limit >= 0) " LIMIT $limit OFFSET ${offset.coerceAtLeast(0)}" else ""
        c.query("SELECT $trackCols FROM tracks $where ORDER BY ${trackOrder(sort)}$window", *args, read = ::trackFrom)
    }

    override fun trackCount(query: String): Int = withDb { c ->
        val (where, args) = trackFilter(query)
        c.query("SELECT COUNT(*) FROM tracks $where", *args) { it.getInt(1) }.firstOrNull() ?: 0
    }

    override fun trackCount(): Int = withDb { c ->
        c.query("SELECT COUNT(*) FROM tracks") { it.getInt(1) }.firstOrNull() ?: 0
    }

    override fun track(id: Long): Track? = withDb { c ->
        c.query("SELECT $trackCols FROM tracks WHERE id=?", id, read = ::trackFrom).firstOrNull()
    }

    override fun tracksByIds(ids: List<Long>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val byId = HashMap<Long, Track>(ids.size)
        withDb { c ->
            ids.chunked(500).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                c.query(
                    "SELECT $trackCols FROM tracks WHERE id IN ($placeholders)",
                    *chunk.toTypedArray<Any?>(), read = ::trackFrom
                ).forEach { byId[it.id] = it }
            }
        }
        return ids.mapNotNull { byId[it] }
    }

    override fun tracksForAlbum(album: String, artist: String): List<Track> = withDb { c ->
        c.query(
            "SELECT $trackCols FROM tracks WHERE album=? AND artist=? ORDER BY track_no, title COLLATE NOCASE",
            album, artist, read = ::trackFrom
        )
    }

    override fun tracksForArtist(artist: String): List<Track> = withDb { c ->
        c.query(
            "SELECT $trackCols FROM tracks WHERE artist=? ORDER BY album COLLATE NOCASE, track_no, title COLLATE NOCASE",
            artist, read = ::trackFrom
        )
    }

    override fun tracksForGenre(genre: String): List<Track> = withDb { c ->
        c.query(
            """SELECT $trackCols FROM tracks WHERE genre=?
               ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE, track_no, title COLLATE NOCASE""",
            genre, read = ::trackFrom
        )
    }

    override fun favorites(): List<Track> = withDb { c ->
        c.query(
            "SELECT $trackCols FROM tracks WHERE favorite=1 ORDER BY title COLLATE NOCASE",
            read = ::trackFrom
        )
    }

    override fun setFavorite(id: Long, fav: Boolean) {
        withDb { c -> c.update("UPDATE tracks SET favorite=? WHERE id=?", fav, id) }
    }

    override fun upsertTrack(
        uri: String, parentUri: String, displayName: String, title: String, artist: String,
        album: String, durationMs: Long, trackNo: Int, genre: String, year: String,
        albumArtist: String, discNo: Int, composer: String,
        hasArt: Boolean, lrcUri: String?, folderId: Long, lastModified: Long
    ) {
        // "comment" is deliberately absent, exactly as on Android: a rescan must
        // not wipe a comment the tag editor saved.
        transaction { c ->
            val updated = c.update(
                """UPDATE tracks SET parent_uri=?, display_name=?, title=?, artist=?, album=?,
                       duration_ms=?, track_no=?, genre=?, year=?, album_artist=?, disc_no=?,
                       composer=?, has_art=?, lrc_uri=?, folder_id=?, last_modified=?
                   WHERE uri=?""",
                parentUri, displayName, title, artist, album, durationMs, trackNo, genre, year,
                albumArtist, discNo, composer, hasArt, lrcUri, folderId, lastModified, uri
            )
            if (updated == 0) {
                c.update(
                    """INSERT INTO tracks(uri, parent_uri, display_name, title, artist, album,
                           duration_ms, track_no, genre, year, album_artist, disc_no, composer,
                           has_art, lrc_uri, folder_id, last_modified, date_added)
                       VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    uri, parentUri, displayName, title, artist, album, durationMs, trackNo, genre, year,
                    albumArtist, discNo, composer, hasArt, lrcUri, folderId, lastModified,
                    System.currentTimeMillis()
                )
            }
        }
    }

    override fun deleteMissingInFolder(folderId: Long, seenUris: Set<String>) {
        transaction { c ->
            val stale = c.query("SELECT id, uri FROM tracks WHERE folder_id=?", folderId) {
                it.getLong(1) to it.getString(2)
            }.filter { it.second !in seenUris }.map { it.first }
            stale.forEach { c.update("DELETE FROM tracks WHERE id=?", it) }
            if (stale.isNotEmpty()) pruneOrphanRows(c)
        }
    }

    override fun updateTags(
        id: Long, title: String, artist: String, album: String, genre: String, year: String,
        trackNo: Int, albumArtist: String, discNo: Int, composer: String, comment: String
    ) {
        withDb { c ->
            c.update(
                """UPDATE tracks SET title=?, artist=?, album=?, genre=?, year=?, track_no=?,
                       album_artist=?, disc_no=?, composer=?, comment=?, last_modified=?
                   WHERE id=?""",
                title, artist, album, genre, year, trackNo, albumArtist, discNo, composer, comment,
                System.currentTimeMillis(), id
            )
        }
    }

    override fun updatePartialTags(
        id: Long, title: String?, artist: String?, album: String?,
        genre: String?, year: String?, trackNo: Int?, albumArtist: String?
    ) {
        val sets = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        title?.let { sets += "title=?"; args += it }
        artist?.let { sets += "artist=?"; args += it }
        album?.let { sets += "album=?"; args += it }
        genre?.let { sets += "genre=?"; args += it }
        year?.let { sets += "year=?"; args += it }
        trackNo?.let { sets += "track_no=?"; args += it }
        albumArtist?.let { sets += "album_artist=?"; args += it }
        if (sets.isEmpty()) return
        sets += "last_modified=?"
        args += System.currentTimeMillis()
        args += id
        withDb { c -> c.update("UPDATE tracks SET ${sets.joinToString(", ")} WHERE id=?", *args.toTypedArray()) }
    }

    override fun updateTrackFile(id: Long, uri: String, displayName: String) {
        withDb { c -> c.update("UPDATE tracks SET uri=?, display_name=? WHERE id=?", uri, displayName, id) }
    }

    override fun updateLrcUri(id: Long, lrcUri: String?) {
        withDb { c -> c.update("UPDATE tracks SET lrc_uri=? WHERE id=?", lrcUri, id) }
    }

    override fun setHasArt(id: Long, hasArt: Boolean) {
        withDb { c -> c.update("UPDATE tracks SET has_art=? WHERE id=?", hasArt, id) }
    }

    override fun removeTrack(id: Long) {
        transaction { c ->
            c.update("DELETE FROM tracks WHERE id=?", id)
            c.update("DELETE FROM playlist_tracks WHERE track_id=?", id)
            c.update("DELETE FROM plays WHERE track_id=?", id)
        }
    }

    // ---------- albums / artists / genres ----------

    override fun albums(sort: String): List<AlbumRow> = withDb { c ->
        val order = when (sort) {
            "artist" -> "artist COLLATE NOCASE, album COLLATE NOCASE"
            "year" -> "year DESC, album COLLATE NOCASE"
            "tracks" -> "n DESC, album COLLATE NOCASE"
            else -> "album COLLATE NOCASE"
        }
        c.query(
            // Prefer a track that actually carries a cover so an album whose art
            // sits on a later file still renders one; fall back to any track.
            """SELECT album, artist, COUNT(*) AS n,
                      COALESCE(NULLIF(MAX(CASE WHEN has_art = 1 THEN id ELSE 0 END), 0), MIN(id)),
                      COALESCE(MIN(NULLIF(SUBSTR(year, 1, 4), '')), '') AS year
               FROM tracks GROUP BY album, artist ORDER BY $order"""
        ) { AlbumRow(it.getString(1), it.getString(2), it.getInt(3), it.getLong(4), it.getString(5)) }
    }

    override fun artists(sort: String): List<ArtistRow> = withDb { c ->
        val order = if (sort == "tracks") "n DESC, artist COLLATE NOCASE" else "artist COLLATE NOCASE"
        c.query("SELECT artist, COUNT(*) AS n, MIN(id) FROM tracks GROUP BY artist ORDER BY $order") {
            ArtistRow(it.getString(1), it.getInt(2), it.getLong(3))
        }
    }

    override fun genres(sort: String): List<GenreRow> = withDb { c ->
        val order = if (sort == "tracks") "n DESC, genre COLLATE NOCASE" else "genre COLLATE NOCASE"
        c.query(
            "SELECT genre, COUNT(*) AS n, MIN(id) FROM tracks WHERE genre <> '' GROUP BY genre ORDER BY $order"
        ) { GenreRow(it.getString(1), it.getInt(2), it.getLong(3)) }
    }

    // ---------- playlists ----------

    override fun createPlaylist(name: String): Long = withDb { c ->
        c.update("INSERT INTO playlists(name, created_at) VALUES(?,?)", name, System.currentTimeMillis())
        c.query("SELECT last_insert_rowid()") { it.getLong(1) }.firstOrNull() ?: 0L
    }

    override fun renamePlaylist(id: Long, name: String) {
        withDb { c -> c.update("UPDATE playlists SET name=? WHERE id=?", name, id) }
    }

    override fun deletePlaylist(id: Long) {
        transaction { c ->
            c.update("DELETE FROM playlist_tracks WHERE playlist_id=?", id)
            c.update("DELETE FROM playlists WHERE id=?", id)
        }
    }

    override fun playlists(): List<Playlist> = withDb { c ->
        c.query(
            """SELECT p.id, p.name, COUNT(pt.track_id) FROM playlists p
               LEFT JOIN playlist_tracks pt ON pt.playlist_id = p.id
               GROUP BY p.id, p.name ORDER BY p.created_at"""
        ) { Playlist(it.getLong(1), it.getString(2), it.getInt(3)) }
    }

    override fun playlist(id: Long): Playlist? = withDb { c ->
        c.query(
            """SELECT p.id, p.name, COUNT(pt.track_id) FROM playlists p
               LEFT JOIN playlist_tracks pt ON pt.playlist_id = p.id
               WHERE p.id=? GROUP BY p.id, p.name""", id
        ) { Playlist(it.getLong(1), it.getString(2), it.getInt(3)) }.firstOrNull()
    }

    override fun addToPlaylist(playlistId: Long, trackId: Long) {
        transaction { c ->
            val next = c.query(
                "SELECT COALESCE(MAX(position),-1)+1 FROM playlist_tracks WHERE playlist_id=?", playlistId
            ) { it.getInt(1) }.firstOrNull() ?: 0
            c.update(
                "INSERT OR IGNORE INTO playlist_tracks(playlist_id, track_id, position) VALUES(?,?,?)",
                playlistId, trackId, next
            )
        }
    }

    override fun removeFromPlaylist(playlistId: Long, trackId: Long) {
        withDb { c ->
            c.update("DELETE FROM playlist_tracks WHERE playlist_id=? AND track_id=?", playlistId, trackId)
        }
    }

    override fun playlistTracks(playlistId: Long): List<Track> = withDb { c ->
        c.query(
            """SELECT ${trackCols.split(", ").joinToString(", ") { "t.$it" }}
               FROM playlist_tracks pt JOIN tracks t ON t.id = pt.track_id
               WHERE pt.playlist_id=? ORDER BY pt.position""",
            playlistId, read = ::trackFrom
        )
    }

    // ---------- listening journal ----------

    override fun recordPlay(trackId: Long, playedAt: Long) {
        withDb { c -> c.update("INSERT INTO plays(track_id, played_at) VALUES(?,?)", trackId, playedAt) }
    }

    /**
     * The whole Listening Journal in one read: library totals, tag health and
     * the heavy-rotation leaders. The "unknown" defaults the scanner writes for
     * untagged files count as missing tags, not as filled ones.
     */
    override fun journalStats(): JournalStats = withDb { c ->
        var trackCount = 0
        var totalDuration = 0L
        var filledTagFields = 0
        var missingArt = 0
        var syncedLyrics = 0
        c.query(
            """SELECT COUNT(*), COALESCE(SUM(duration_ms),0),
                      COALESCE(SUM(
                        (CASE WHEN artist <> '' AND artist <> ? THEN 1 ELSE 0 END) +
                        (CASE WHEN album <> '' AND album <> ? THEN 1 ELSE 0 END) +
                        (CASE WHEN genre <> '' THEN 1 ELSE 0 END) +
                        (CASE WHEN year <> '' THEN 1 ELSE 0 END) +
                        (CASE WHEN track_no > 0 THEN 1 ELSE 0 END) +
                        (CASE WHEN album_artist <> '' THEN 1 ELSE 0 END)
                      ),0),
                      COALESCE(SUM(CASE WHEN has_art = 0 THEN 1 ELSE 0 END),0),
                      COALESCE(SUM(CASE WHEN lrc_uri IS NOT NULL AND lrc_uri <> '' THEN 1 ELSE 0 END),0)
               FROM tracks""",
            UNKNOWN_ARTIST, UNKNOWN_ALBUM
        ) {
            trackCount = it.getInt(1); totalDuration = it.getLong(2); filledTagFields = it.getInt(3)
            missingArt = it.getInt(4); syncedLyrics = it.getInt(5)
        }

        val albumCount = c.query("SELECT COUNT(*) FROM (SELECT 1 FROM tracks GROUP BY album, artist)") {
            it.getInt(1)
        }.firstOrNull() ?: 0
        val totalPlays = c.query("SELECT COUNT(*) FROM plays") { it.getInt(1) }.firstOrNull() ?: 0

        val topSongs = c.query(
            """SELECT t.title, t.artist, COUNT(*) AS plays
               FROM plays p JOIN tracks t ON t.id = p.track_id
               GROUP BY p.track_id ORDER BY plays DESC, t.title COLLATE NOCASE LIMIT ?""",
            JOURNAL_TOP_N
        ) { TopTrack(it.getString(1), it.getString(2), it.getInt(3)) }

        val topArtists = c.query(
            """SELECT t.artist, COUNT(*) AS plays
               FROM plays p JOIN tracks t ON t.id = p.track_id
               GROUP BY t.artist ORDER BY plays DESC, t.artist COLLATE NOCASE LIMIT ?""",
            JOURNAL_TOP_N
        ) { TopArtist(it.getString(1), it.getInt(2)) }

        // MAX(...has_art...) picks a track that actually carries a cover, so an
        // album whose art sits on a later track still renders one; 0 when none do.
        val topAlbums = c.query(
            """SELECT t.album, t.artist, COUNT(*) AS plays,
                      MAX(CASE WHEN t.has_art = 1 THEN t.id ELSE 0 END)
               FROM plays p JOIN tracks t ON t.id = p.track_id
               WHERE t.album <> '' AND t.album <> ?
               GROUP BY t.album, t.artist ORDER BY plays DESC, t.album COLLATE NOCASE LIMIT ?""",
            UNKNOWN_ALBUM, JOURNAL_TOP_N
        ) { TopAlbum(it.getString(1), it.getString(2), it.getInt(3), it.getLong(4)) }

        val topGenres = c.query(
            """SELECT t.genre, COUNT(*) AS plays
               FROM plays p JOIN tracks t ON t.id = p.track_id
               WHERE t.genre <> ''
               GROUP BY t.genre ORDER BY plays DESC, t.genre COLLATE NOCASE LIMIT ?""",
            JOURNAL_TOP_N
        ) { TopGenre(it.getString(1), it.getInt(2)) }

        // played_at is a wall-clock millisecond stamp; SQLite's date functions
        // take seconds, and 'localtime' buckets by the machine's current zone.
        val peakHour = c.query(
            """SELECT CAST(strftime('%H', played_at / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
                      COUNT(*) AS plays
               FROM plays GROUP BY hour ORDER BY plays DESC, hour LIMIT 1"""
        ) { PeakHour(it.getInt(1), it.getInt(2)) }.firstOrNull()

        val peakDay = c.query(
            """SELECT CAST(strftime('%w', played_at / 1000, 'unixepoch', 'localtime') AS INTEGER) AS day,
                      COUNT(*) AS plays
               FROM plays GROUP BY day ORDER BY plays DESC, day LIMIT 1"""
        ) { PeakDay(it.getInt(1), it.getInt(2)) }.firstOrNull()

        // Only tracks still in the library count as explored — the progress bar
        // compares against the current track count.
        val exploredTracks = c.query(
            "SELECT COUNT(DISTINCT p.track_id) FROM plays p JOIN tracks t ON t.id = p.track_id"
        ) { it.getInt(1) }.firstOrNull() ?: 0

        val topDecade = c.query(
            """SELECT SUBSTR(year, 1, 3) AS dec, COUNT(*) AS n FROM tracks
               WHERE year GLOB '[0-9][0-9][0-9][0-9]*'
               GROUP BY dec ORDER BY n DESC, dec DESC LIMIT 1"""
        ) { it.getString(1) + "0s" }.firstOrNull() ?: ""

        JournalStats(
            trackCount = trackCount,
            albumCount = albumCount,
            totalDurationMs = totalDuration,
            filledTagFields = filledTagFields,
            totalTagFields = trackCount * CORE_TAG_FIELDS,
            missingArt = missingArt,
            syncedLyrics = syncedLyrics,
            totalPlays = totalPlays,
            topSongs = topSongs,
            topArtists = topArtists,
            topAlbums = topAlbums,
            topGenres = topGenres,
            peakHour = peakHour,
            peakDay = peakDay,
            exploredTracks = exploredTracks,
            longestStreak = longestPlayStreak(c),
            formats = formatShares(c),
            topDecade = topDecade,
            folderCount = c.query("SELECT COUNT(*) FROM folders") { it.getInt(1) }.firstOrNull() ?: 0
        )
    }

    /**
     * The longest run of consecutive local days that each logged a play.
     * julianday() over the local date gives one integer per calendar day, so
     * consecutive days differ by exactly one however long the run is.
     */
    private fun longestPlayStreak(c: Connection): Int {
        var best = 0
        var run = 0
        var prev = Long.MIN_VALUE
        c.query(
            """SELECT DISTINCT CAST(julianday(strftime('%Y-%m-%d', played_at / 1000, 'unixepoch', 'localtime')) AS INTEGER) AS d
               FROM plays ORDER BY d"""
        ) { it.getLong(1) }.forEach { day ->
            run = if (day == prev + 1) run + 1 else 1
            if (run > best) best = run
            prev = day
        }
        return best
    }

    /**
     * Extension breakdown of the library, commonest first. Done in Kotlin
     * because SQLite has no reverse-find, and a filename may hold several dots.
     */
    private fun formatShares(c: Connection): List<FormatShare> {
        val counts = mutableMapOf<String, Int>()
        c.query("SELECT display_name FROM tracks") { it.getString(1) ?: "" }.forEach { name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isNotEmpty() && ext.length <= 5) counts[ext] = (counts[ext] ?: 0) + 1
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { FormatShare(it.key, it.value) }
    }

    companion object {
        /** XDG data dir, so the library survives upgrades and reinstalls. */
        fun defaultDbFile(): File {
            val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.local/share")
            return File("$base/poet-music/library.db")
        }
    }
}
