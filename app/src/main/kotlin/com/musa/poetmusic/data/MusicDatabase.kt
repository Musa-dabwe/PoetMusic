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
    private fun trackFilter(query: String): Pair<String, Array<String>> =
        if (query.isBlank()) "" to emptyArray()
        else "WHERE title LIKE ? OR artist LIKE ? OR album LIKE ?" to arrayOf("%$query%", "%$query%", "%$query%")

    /**
     * Songs matching [query] in [sort] order. [limit] < 0 reads the whole
     * library; a non-negative limit windows the read so the songs tab can
     * render one page at a time instead of every row on every keystroke.
     */
    fun tracks(query: String = "", sort: String = "title", limit: Int = -1, offset: Int = 0): List<Track> {
        val out = mutableListOf<Track>()
        val (where, args) = trackFilter(query)
        val window = if (limit >= 0) " LIMIT $limit OFFSET ${offset.coerceAtLeast(0)}" else ""
        readableDatabase.rawQuery(
            "SELECT $trackCols FROM tracks $where ORDER BY ${trackOrder(sort)}$window", args
        ).use { c ->
            while (c.moveToNext()) out += trackFrom(c)
        }
        return out
    }

    /** How many songs [query] matches — the total behind a windowed read. */
    fun trackCount(query: String): Int {
        val (where, args) = trackFilter(query)
        readableDatabase.rawQuery("SELECT COUNT(*) FROM tracks $where", args).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
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

    /**
     * Batch-editing counterpart to [updateTags]: null means "leave this column
     * alone", so a batch that only sets the artist can't blank out every other
     * field of the selected tracks.
     */
    fun updatePartialTags(
        id: Long, title: String? = null, artist: String? = null, album: String? = null,
        genre: String? = null, year: String? = null, trackNo: Int? = null, albumArtist: String? = null
    ) {
        val cv = ContentValues().apply {
            title?.let { put("title", it) }
            artist?.let { put("artist", it) }
            album?.let { put("album", it) }
            genre?.let { put("genre", it) }
            year?.let { put("year", it) }
            trackNo?.let { put("track_no", it) }
            albumArtist?.let { put("album_artist", it) }
        }
        if (cv.size() == 0) return
        cv.put("last_modified", System.currentTimeMillis())
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

    // ---------- albums / artists / genres ----------

    /**
     * Albums grouped by (album, artist). [sort] mirrors the album sort drawer:
     * title, artist, year or track count. The year shown is the earliest
     * four-digit year tagged on any of the album's tracks, so a compilation
     * with one mistagged file still sorts sensibly.
     */
    fun albums(sort: String = "title"): List<AlbumRow> {
        val order = when (sort) {
            "artist" -> "artist COLLATE NOCASE, album COLLATE NOCASE"
            "year" -> "year DESC, album COLLATE NOCASE"
            "tracks" -> "n DESC, album COLLATE NOCASE"
            else -> "album COLLATE NOCASE"
        }
        val out = mutableListOf<AlbumRow>()
        readableDatabase.rawQuery(
            // Prefer a track that actually carries a cover so an album whose art
            // sits on a later file still renders one; fall back to any track,
            // which at least gives the tile its pastel tint.
            """SELECT album, artist, COUNT(*) AS n,
                      COALESCE(NULLIF(MAX(CASE WHEN has_art = 1 THEN id ELSE 0 END), 0), MIN(id)),
                      COALESCE(MIN(NULLIF(SUBSTR(year, 1, 4), '')), '') AS year
               FROM tracks GROUP BY album, artist ORDER BY $order""", null
        ).use { c ->
            while (c.moveToNext()) {
                out += AlbumRow(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3), c.getString(4))
            }
        }
        return out
    }

    fun artists(sort: String = "name"): List<ArtistRow> {
        val order = if (sort == "tracks") "n DESC, artist COLLATE NOCASE" else "artist COLLATE NOCASE"
        val out = mutableListOf<ArtistRow>()
        readableDatabase.rawQuery(
            """SELECT artist, COUNT(*) AS n, MIN(id) FROM tracks
               GROUP BY artist ORDER BY $order""", null
        ).use { c -> while (c.moveToNext()) out += ArtistRow(c.getString(0), c.getInt(1), c.getLong(2)) }
        return out
    }

    /** Tagged genres with their track counts; untagged tracks are excluded. */
    fun genres(sort: String = "name"): List<GenreRow> {
        val order = if (sort == "tracks") "n DESC, genre COLLATE NOCASE" else "genre COLLATE NOCASE"
        val out = mutableListOf<GenreRow>()
        readableDatabase.rawQuery(
            """SELECT genre, COUNT(*) AS n, MIN(id) FROM tracks
               WHERE genre <> '' GROUP BY genre ORDER BY $order""", null
        ).use { c -> while (c.moveToNext()) out += GenreRow(c.getString(0), c.getInt(1), c.getLong(2)) }
        return out
    }

    fun tracksForGenre(genre: String): List<Track> {
        val out = mutableListOf<Track>()
        readableDatabase.rawQuery(
            """SELECT $trackCols FROM tracks WHERE genre=?
               ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE, track_no, title COLLATE NOCASE""",
            arrayOf(genre)
        ).use { c -> while (c.moveToNext()) out += trackFrom(c) }
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

        // JOURNAL_TOP_N caps every leaderboard; the journal screen renders
        // whatever these reads return.
        val topSongs = mutableListOf<TopTrack>()
        db.rawQuery(
            """SELECT t.title, t.artist, COUNT(*) AS plays
               FROM plays p JOIN tracks t ON t.id = p.track_id
               GROUP BY p.track_id ORDER BY plays DESC, t.title COLLATE NOCASE LIMIT ?""",
            arrayOf(JOURNAL_TOP_N.toString())
        ).use { c -> while (c.moveToNext()) topSongs += TopTrack(c.getString(0), c.getString(1), c.getInt(2)) }

        val topArtists = mutableListOf<TopArtist>()
        db.rawQuery(
            """SELECT t.artist, COUNT(*) AS plays
               FROM plays p JOIN tracks t ON t.id = p.track_id
               GROUP BY t.artist ORDER BY plays DESC, t.artist COLLATE NOCASE LIMIT ?""",
            arrayOf(JOURNAL_TOP_N.toString())
        ).use { c -> while (c.moveToNext()) topArtists += TopArtist(c.getString(0), c.getInt(1)) }

        // MAX(...has_art...) picks a track that actually carries a cover, so an
        // album whose art sits on a later track still renders one; 0 when none do.
        val topAlbums = mutableListOf<TopAlbum>()
        db.rawQuery(
            """SELECT t.album, t.artist, COUNT(*) AS plays,
                      MAX(CASE WHEN t.has_art = 1 THEN t.id ELSE 0 END)
               FROM plays p JOIN tracks t ON t.id = p.track_id
               WHERE t.album <> '' AND t.album <> ?
               GROUP BY t.album, t.artist ORDER BY plays DESC, t.album COLLATE NOCASE LIMIT ?""",
            arrayOf(UNKNOWN_ALBUM, JOURNAL_TOP_N.toString())
        ).use { c ->
            while (c.moveToNext()) topAlbums += TopAlbum(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3))
        }

        val topGenres = mutableListOf<TopGenre>()
        db.rawQuery(
            """SELECT t.genre, COUNT(*) AS plays
               FROM plays p JOIN tracks t ON t.id = p.track_id
               WHERE t.genre <> ''
               GROUP BY t.genre ORDER BY plays DESC, t.genre COLLATE NOCASE LIMIT ?""",
            arrayOf(JOURNAL_TOP_N.toString())
        ).use { c -> while (c.moveToNext()) topGenres += TopGenre(c.getString(0), c.getInt(1)) }

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

        var peakDay: PeakDay? = null
        db.rawQuery(
            """SELECT CAST(strftime('%w', played_at / 1000, 'unixepoch', 'localtime') AS INTEGER) AS day,
                      COUNT(*) AS plays
               FROM plays GROUP BY day ORDER BY plays DESC, day LIMIT 1""", null
        ).use { c ->
            if (c.moveToFirst()) peakDay = PeakDay(c.getInt(0), c.getInt(1))
        }

        // Only tracks still in the library count as explored — the progress bar
        // compares against the current track count, and plays of since-removed
        // files would push it past 100%.
        var exploredTracks = 0
        db.rawQuery(
            "SELECT COUNT(DISTINCT p.track_id) FROM plays p JOIN tracks t ON t.id = p.track_id", null
        ).use { c -> if (c.moveToFirst()) exploredTracks = c.getInt(0) }

        var topDecade = ""
        db.rawQuery(
            """SELECT SUBSTR(year, 1, 3) AS dec, COUNT(*) AS n FROM tracks
               WHERE year GLOB '[0-9][0-9][0-9][0-9]*'
               GROUP BY dec ORDER BY n DESC, dec DESC LIMIT 1""", null
        ).use { c -> if (c.moveToFirst()) topDecade = c.getString(0) + "0s" }

        return JournalStats(
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
            longestStreak = longestPlayStreak(),
            formats = formatShares(),
            topDecade = topDecade,
            folderCount = folders().size
        )
    }

    /**
     * The longest run of consecutive local days that each logged a play.
     * julianday() over the local date gives one integer per calendar day, so
     * consecutive days differ by exactly one however long the run is.
     */
    private fun longestPlayStreak(): Int {
        var best = 0
        var run = 0
        var prev = Long.MIN_VALUE
        readableDatabase.rawQuery(
            """SELECT DISTINCT CAST(julianday(strftime('%Y-%m-%d', played_at / 1000, 'unixepoch', 'localtime')) AS INTEGER) AS d
               FROM plays ORDER BY d""", null
        ).use { c ->
            while (c.moveToNext()) {
                val day = c.getLong(0)
                run = if (day == prev + 1) run + 1 else 1
                if (run > best) best = run
                prev = day
            }
        }
        return best
    }

    /**
     * Extension breakdown of the library, commonest first. Done in Kotlin
     * because SQLite has no reverse-find, and a filename may hold several dots.
     */
    private fun formatShares(): List<FormatShare> {
        val counts = mutableMapOf<String, Int>()
        readableDatabase.rawQuery("SELECT display_name FROM tracks", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: ""
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext.isNotEmpty() && ext.length <= 5) counts[ext] = (counts[ext] ?: 0) + 1
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { FormatShare(it.key, it.value) }
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

        /** How deep each journal leaderboard is read, and shown. */
        const val JOURNAL_TOP_N = 10
    }
}
