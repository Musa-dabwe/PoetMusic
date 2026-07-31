package com.musa.poetmusic.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite library store. Runs in WAL mode with a connection pool so
 * background scan writes don't block htmx read requests; compound
 * read-then-write operations are wrapped in transactions instead of
 * coarse method-level locks. The WAL file is capped at 2 MB and freed
 * pages are returned to the OS via incremental auto-vacuum, keeping the
 * on-disk footprint tight.
 */
class MusicDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, "poet_music.db", null, 4) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        // Stop the -wal file from growing indefinitely between checkpoints.
        db.rawQuery("PRAGMA journal_size_limit=2097152", null).use { it.moveToFirst() }
    }

    override fun onOpen(db: SQLiteDatabase) {
        if (db.isReadOnly) return
        val autoVacuum = db.rawQuery("PRAGMA auto_vacuum", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        if (autoVacuum != 2) {
            // Switching auto_vacuum only takes effect after a full VACUUM;
            // the library db is a few MB at most, so this one-off is cheap.
            db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
            db.execSQL("VACUUM")
        } else {
            db.rawQuery("PRAGMA incremental_vacuum(128)", null).use { it.moveToFirst() }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tracks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uri TEXT UNIQUE NOT NULL,
                parent_uri TEXT NOT NULL DEFAULT '',
                display_name TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL DEFAULT '',
                artist TEXT NOT NULL DEFAULT 'Unknown artist',
                album TEXT NOT NULL DEFAULT 'Unknown album',
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
        db.execSQL(
            """CREATE TABLE folders(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tree_uri TEXT UNIQUE NOT NULL,
                display_path TEXT NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE playlists(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE playlist_tracks(
                playlist_id INTEGER NOT NULL,
                track_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY(playlist_id, track_id)
            )"""
        )
        db.execSQL(
            """CREATE TABLE settings(
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )"""
        )
        createPlaysTable(db)
    }

    /** Append-only listening log behind the Listening Journal's play counts. */
    private fun createPlaysTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE plays(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                track_id INTEGER NOT NULL,
                played_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_plays_track ON plays(track_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN genre TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tracks ADD COLUMN year TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tracks ADD COLUMN last_modified INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN album_artist TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tracks ADD COLUMN disc_no INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE tracks ADD COLUMN composer TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tracks ADD COLUMN comment TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 4) {
            createPlaysTable(db)
        }
    }

    /** Runs [block] in a transaction that still allows concurrent WAL readers. */
    private inline fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransactionNonExclusive()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    // ---------- settings ----------

    fun getSetting(key: String, default: String): String {
        readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else default
        }
    }

    fun setSetting(key: String, value: String) {
        val cv = ContentValues().apply { put("key", key); put("value", value) }
        writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------- folders ----------

    fun addFolder(treeUri: String, displayPath: String): Long {
        val cv = ContentValues().apply { put("tree_uri", treeUri); put("display_path", displayPath) }
        return writableDatabase.insertWithOnConflict("folders", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun removeFolder(id: Long) {
        val db = writableDatabase
        transaction(db) {
            db.delete("tracks", "folder_id=?", arrayOf(id.toString()))
            db.delete("folders", "id=?", arrayOf(id.toString()))
            pruneOrphanRows(db)
        }
    }

    fun folders(): List<MusicFolder> {
        val out = mutableListOf<MusicFolder>()
        readableDatabase.rawQuery("SELECT id, tree_uri, display_path FROM folders ORDER BY id", null).use { c ->
            while (c.moveToNext()) out += MusicFolder(c.getLong(0), c.getString(1), c.getString(2))
        }
        return out
    }

    // ---------- tracks ----------

    fun upsertTrack(
        uri: String, parentUri: String, displayName: String, title: String, artist: String,
        album: String, durationMs: Long, trackNo: Int, genre: String, year: String,
        albumArtist: String, discNo: Int, composer: String,
        hasArt: Boolean, lrcUri: String?, folderId: Long, lastModified: Long
    ) {
        val db = writableDatabase
        // "comment" is deliberately absent: MediaMetadataRetriever can't read
        // COMM frames, so rescans must not wipe a comment saved by the editor.
        val cv = ContentValues().apply {
            put("uri", uri); put("parent_uri", parentUri); put("display_name", displayName)
            put("title", title); put("artist", artist); put("album", album)
            put("duration_ms", durationMs); put("track_no", trackNo)
            put("genre", genre); put("year", year)
            put("album_artist", albumArtist); put("disc_no", discNo); put("composer", composer)
            put("has_art", if (hasArt) 1 else 0); put("lrc_uri", lrcUri); put("folder_id", folderId)
            put("last_modified", lastModified)
        }
        transaction(db) {
            val updated = db.update("tracks", cv, "uri=?", arrayOf(uri))
            if (updated == 0) {
                cv.put("date_added", System.currentTimeMillis())
                db.insert("tracks", null, cv)
            }
        }
    }

    fun deleteMissingInFolder(folderId: Long, seenUris: Set<String>) {
        val db = writableDatabase
        transaction(db) {
            val stale = mutableListOf<Long>()
            db.rawQuery("SELECT id, uri FROM tracks WHERE folder_id=?", arrayOf(folderId.toString())).use { c ->
                while (c.moveToNext()) if (c.getString(1) !in seenUris) stale += c.getLong(0)
            }
            stale.forEach { db.delete("tracks", "id=?", arrayOf(it.toString())) }
            if (stale.isNotEmpty()) pruneOrphanRows(db)
        }
    }

    /** Drop playlist entries and journal plays that point at tracks that are gone. */
    private fun pruneOrphanRows(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM playlist_tracks WHERE track_id NOT IN (SELECT id FROM tracks)")
        db.execSQL("DELETE FROM plays WHERE track_id NOT IN (SELECT id FROM tracks)")
    }

    private fun trackFrom(c: Cursor) = Track(
        id = c.getLong(0), uri = c.getString(1), parentUri = c.getString(2), displayName = c.getString(3),
        title = c.getString(4), artist = c.getString(5), album = c.getString(6), durationMs = c.getLong(7),
        trackNo = c.getInt(8), genre = c.getString(9), year = c.getString(10),
        hasArt = c.getInt(11) == 1, lrcUri = c.getString(12), folderId = c.getLong(13),
        favorite = c.getInt(14) == 1, dateAdded = c.getLong(15), lastModified = c.getLong(16),
        albumArtist = c.getString(17), discNo = c.getInt(18), composer = c.getString(19), comment = c.getString(20)
    )

    private val trackCols =
        "id, uri, parent_uri, display_name, title, artist, album, duration_ms, track_no, genre, year, has_art, lrc_uri, folder_id, favorite, date_added, last_modified, album_artist, disc_no, composer, comment"

    fun tracks(query: String = "", sort: String = "title"): List<Track> {
        val order = when (sort) {
            "title_desc" -> "title COLLATE NOCASE DESC"
            "artist" -> "artist COLLATE NOCASE, title COLLATE NOCASE"
            "artist_desc" -> "artist COLLATE NOCASE DESC, title COLLATE NOCASE"
            "date_modified" -> "last_modified DESC, date_added DESC, id DESC"
            "date_added" -> "date_added ASC, id ASC"
            "recent" -> "date_added DESC"
            "duration" -> "duration_ms DESC"
            else -> "title COLLATE NOCASE"
        }
        val out = mutableListOf<Track>()
        val (where, args) = if (query.isBlank()) "" to emptyArray<String>()
        else "WHERE title LIKE ? OR artist LIKE ? OR album LIKE ?" to arrayOf("%$query%", "%$query%", "%$query%")
        readableDatabase.rawQuery("SELECT $trackCols FROM tracks $where ORDER BY $order", args).use { c ->
            while (c.moveToNext()) out += trackFrom(c)
        }
        return out
    }

    fun track(id: Long): Track? {
        readableDatabase.rawQuery("SELECT $trackCols FROM tracks WHERE id=?", arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) trackFrom(c) else null
        }
    }

    /** Tracks looked up by id, returned in the order of [ids]; missing ids are skipped. */
    fun tracksByIds(ids: List<Long>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val byId = HashMap<Long, Track>(ids.size)
        ids.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT $trackCols FROM tracks WHERE id IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray()
            ).use { c -> while (c.moveToNext()) trackFrom(c).let { byId[it.id] = it } }
        }
        return ids.mapNotNull { byId[it] }
    }

    fun tracksForAlbum(album: String, artist: String): List<Track> {
        val out = mutableListOf<Track>()
        readableDatabase.rawQuery(
            "SELECT $trackCols FROM tracks WHERE album=? AND artist=? ORDER BY track_no, title COLLATE NOCASE",
            arrayOf(album, artist)
        ).use { c -> while (c.moveToNext()) out += trackFrom(c) }
        return out
    }

    fun tracksForArtist(artist: String): List<Track> {
        val out = mutableListOf<Track>()
        readableDatabase.rawQuery(
            "SELECT $trackCols FROM tracks WHERE artist=? ORDER BY album COLLATE NOCASE, track_no, title COLLATE NOCASE",
            arrayOf(artist)
        ).use { c -> while (c.moveToNext()) out += trackFrom(c) }
        return out
    }

    fun favorites(): List<Track> {
        val out = mutableListOf<Track>()
        readableDatabase.rawQuery(
            "SELECT $trackCols FROM tracks WHERE favorite=1 ORDER BY title COLLATE NOCASE", null
        ).use { c -> while (c.moveToNext()) out += trackFrom(c) }
        return out
    }

    fun setFavorite(id: Long, fav: Boolean) {
        writableDatabase.execSQL("UPDATE tracks SET favorite=? WHERE id=?", arrayOf(if (fav) 1 else 0, id))
    }

    fun updateTags(
        id: Long, title: String, artist: String, album: String, genre: String, year: String,
        trackNo: Int, albumArtist: String, discNo: Int, composer: String, comment: String
    ) {
        val cv = ContentValues().apply {
            put("title", title); put("artist", artist); put("album", album)
            put("genre", genre); put("year", year); put("track_no", trackNo)
            put("album_artist", albumArtist); put("disc_no", discNo)
            put("composer", composer); put("comment", comment)
            put("last_modified", System.currentTimeMillis())
        }
        writableDatabase.update("tracks", cv, "id=?", arrayOf(id.toString()))
    }

    /** Points a track row at its new document after a physical file rename. */
    fun updateTrackFile(id: Long, uri: String, displayName: String) {
        val cv = ContentValues().apply { put("uri", uri); put("display_name", displayName) }
        writableDatabase.update("tracks", cv, "id=?", arrayOf(id.toString()))
    }

    fun updateLrcUri(id: Long, lrcUri: String?) {
        val cv = ContentValues().apply { put("lrc_uri", lrcUri) }
        writableDatabase.update("tracks", cv, "id=?", arrayOf(id.toString()))
    }

    fun setHasArt(id: Long, hasArt: Boolean) {
        writableDatabase.execSQL("UPDATE tracks SET has_art=? WHERE id=?", arrayOf(if (hasArt) 1 else 0, id))
    }

    fun removeTrack(id: Long) {
        val db = writableDatabase
        transaction(db) {
            db.delete("tracks", "id=?", arrayOf(id.toString()))
            db.delete("playlist_tracks", "track_id=?", arrayOf(id.toString()))
            db.delete("plays", "track_id=?", arrayOf(id.toString()))
        }
    }

    fun trackCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM tracks", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // ---------- albums / artists ----------

    fun albums(): List<AlbumRow> {
        val out = mutableListOf<AlbumRow>()
        readableDatabase.rawQuery(
            """SELECT album, artist, COUNT(*), MIN(id) FROM tracks
               GROUP BY album, artist ORDER BY album COLLATE NOCASE""", null
        ).use { c -> while (c.moveToNext()) out += AlbumRow(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3)) }
        return out
    }

    fun artists(): List<ArtistRow> {
        val out = mutableListOf<ArtistRow>()
        readableDatabase.rawQuery(
            """SELECT artist, COUNT(*), MIN(id) FROM tracks
               GROUP BY artist ORDER BY artist COLLATE NOCASE""", null
        ).use { c -> while (c.moveToNext()) out += ArtistRow(c.getString(0), c.getInt(1), c.getLong(2)) }
        return out
    }

    // ---------- listening journal ----------

    /** Append one listen to the plays log (see PlayerController's play threshold). */
    fun recordPlay(trackId: Long, playedAt: Long = System.currentTimeMillis()) {
        val cv = ContentValues().apply { put("track_id", trackId); put("played_at", playedAt) }
        writableDatabase.insert("plays", null, cv)
    }

    /**
     * The whole Listening Journal in one read: library totals, tag health and
     * the heavy-rotation leaders. The "unknown" defaults the scanner writes for
     * untagged files count as missing tags, not as filled ones.
     */
    fun journalStats(): JournalStats {
        val db = readableDatabase
        var trackCount = 0
        var totalDuration = 0L
        var filledTagFields = 0
        var missingArt = 0
        var syncedLyrics = 0
        db.rawQuery(
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
            arrayOf(UNKNOWN_ARTIST, UNKNOWN_ALBUM)
        ).use { c ->
            if (c.moveToFirst()) {
                trackCount = c.getInt(0)
                totalDuration = c.getLong(1)
                filledTagFields = c.getInt(2)
                missingArt = c.getInt(3)
                syncedLyrics = c.getInt(4)
            }
        }

        var albumCount = 0
        db.rawQuery("SELECT COUNT(*) FROM (SELECT 1 FROM tracks GROUP BY album, artist)", null).use { c ->
            if (c.moveToFirst()) albumCount = c.getInt(0)
        }

        var totalPlays = 0
        db.rawQuery("SELECT COUNT(*) FROM plays", null).use { c ->
            if (c.moveToFirst()) totalPlays = c.getInt(0)
        }

        var topTrack: TopTrack? = null
        db.rawQuery(
            """SELECT t.title, t.artist, COUNT(*) AS plays
               FROM plays p JOIN tracks t ON t.id = p.track_id
               GROUP BY p.track_id ORDER BY plays DESC, t.title COLLATE NOCASE LIMIT 1""", null
        ).use { c ->
            if (c.moveToFirst()) topTrack = TopTrack(c.getString(0), c.getString(1), c.getInt(2))
        }

        var topArtist: TopArtist? = null
        db.rawQuery(
            """SELECT t.artist, COUNT(*) AS plays
               FROM plays p JOIN tracks t ON t.id = p.track_id
               GROUP BY t.artist ORDER BY plays DESC, t.artist COLLATE NOCASE LIMIT 1""", null
        ).use { c ->
            if (c.moveToFirst()) topArtist = TopArtist(c.getString(0), c.getInt(1))
        }

        // played_at is a wall-clock millisecond stamp; SQLite's date functions
        // take seconds, and 'localtime' buckets by the device's current zone.
        var peakHour: PeakHour? = null
        db.rawQuery(
            """SELECT CAST(strftime('%H', played_at / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
                      COUNT(*) AS plays
               FROM plays GROUP BY hour ORDER BY plays DESC, hour LIMIT 1""", null
        ).use { c ->
            if (c.moveToFirst()) peakHour = PeakHour(c.getInt(0), c.getInt(1))
        }

        return JournalStats(
            trackCount = trackCount,
            albumCount = albumCount,
            totalDurationMs = totalDuration,
            filledTagFields = filledTagFields,
            totalTagFields = trackCount * CORE_TAG_FIELDS,
            missingArt = missingArt,
            syncedLyrics = syncedLyrics,
            totalPlays = totalPlays,
            topTrack = topTrack,
            topArtist = topArtist,
            peakHour = peakHour
        )
    }

    // ---------- playlists ----------

    fun createPlaylist(name: String): Long {
        val cv = ContentValues().apply { put("name", name); put("created_at", System.currentTimeMillis()) }
        return writableDatabase.insert("playlists", null, cv)
    }

    fun renamePlaylist(id: Long, name: String) {
        val cv = ContentValues().apply { put("name", name) }
        writableDatabase.update("playlists", cv, "id=?", arrayOf(id.toString()))
    }

    fun deletePlaylist(id: Long) {
        val db = writableDatabase
        transaction(db) {
            db.delete("playlist_tracks", "playlist_id=?", arrayOf(id.toString()))
            db.delete("playlists", "id=?", arrayOf(id.toString()))
        }
    }

    fun playlists(): List<Playlist> {
        val out = mutableListOf<Playlist>()
        readableDatabase.rawQuery(
            """SELECT p.id, p.name, COUNT(pt.track_id) FROM playlists p
               LEFT JOIN playlist_tracks pt ON pt.playlist_id = p.id
               GROUP BY p.id, p.name ORDER BY p.created_at""", null
        ).use { c -> while (c.moveToNext()) out += Playlist(c.getLong(0), c.getString(1), c.getInt(2)) }
        return out
    }

    fun playlist(id: Long): Playlist? {
        readableDatabase.rawQuery(
            """SELECT p.id, p.name, COUNT(pt.track_id) FROM playlists p
               LEFT JOIN playlist_tracks pt ON pt.playlist_id = p.id
               WHERE p.id=? GROUP BY p.id, p.name""", arrayOf(id.toString())
        ).use { c -> return if (c.moveToFirst() && !c.isNull(0)) Playlist(c.getLong(0), c.getString(1), c.getInt(2)) else null }
    }

    fun addToPlaylist(playlistId: Long, trackId: Long) {
        val db = writableDatabase
        transaction(db) {
            var next = 0
            db.rawQuery(
                "SELECT COALESCE(MAX(position),-1)+1 FROM playlist_tracks WHERE playlist_id=?",
                arrayOf(playlistId.toString())
            ).use { c -> if (c.moveToFirst()) next = c.getInt(0) }
            val cv = ContentValues().apply {
                put("playlist_id", playlistId); put("track_id", trackId); put("position", next)
            }
            db.insertWithOnConflict("playlist_tracks", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun removeFromPlaylist(playlistId: Long, trackId: Long) {
        writableDatabase.delete(
            "playlist_tracks", "playlist_id=? AND track_id=?",
            arrayOf(playlistId.toString(), trackId.toString())
        )
    }

    fun playlistTracks(playlistId: Long): List<Track> {
        val out = mutableListOf<Track>()
        readableDatabase.rawQuery(
            """SELECT ${trackCols.split(", ").joinToString(", ") { "t.$it" }}
               FROM playlist_tracks pt JOIN tracks t ON t.id = pt.track_id
               WHERE pt.playlist_id=? ORDER BY pt.position""", arrayOf(playlistId.toString())
        ).use { c -> while (c.moveToNext()) out += trackFrom(c) }
        return out
    }

    companion object {
        /** Placeholders the scanner writes when a file carries no artist/album tag. */
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"

        /**
         * Tag fields the journal's integrity index scores per track: artist,
         * album, genre, year, track number and album artist.
         */
        const val CORE_TAG_FIELDS = 6
    }
}
