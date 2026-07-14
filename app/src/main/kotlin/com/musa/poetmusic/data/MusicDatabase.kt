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
class MusicDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, "poet_music.db", null, 2) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN genre TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tracks ADD COLUMN year TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tracks ADD COLUMN last_modified INTEGER NOT NULL DEFAULT 0")
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
            pruneOrphanPlaylistEntries(db)
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
        hasArt: Boolean, lrcUri: String?, folderId: Long, lastModified: Long
    ) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("uri", uri); put("parent_uri", parentUri); put("display_name", displayName)
            put("title", title); put("artist", artist); put("album", album)
            put("duration_ms", durationMs); put("track_no", trackNo)
            put("genre", genre); put("year", year)
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
            if (stale.isNotEmpty()) pruneOrphanPlaylistEntries(db)
        }
    }

    private fun pruneOrphanPlaylistEntries(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM playlist_tracks WHERE track_id NOT IN (SELECT id FROM tracks)")
    }

    private fun trackFrom(c: Cursor) = Track(
        id = c.getLong(0), uri = c.getString(1), parentUri = c.getString(2), displayName = c.getString(3),
        title = c.getString(4), artist = c.getString(5), album = c.getString(6), durationMs = c.getLong(7),
        trackNo = c.getInt(8), genre = c.getString(9), year = c.getString(10),
        hasArt = c.getInt(11) == 1, lrcUri = c.getString(12), folderId = c.getLong(13),
        favorite = c.getInt(14) == 1, dateAdded = c.getLong(15), lastModified = c.getLong(16)
    )

    private val trackCols =
        "id, uri, parent_uri, display_name, title, artist, album, duration_ms, track_no, genre, year, has_art, lrc_uri, folder_id, favorite, date_added, last_modified"

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

    fun updateTags(id: Long, title: String, artist: String, album: String, genre: String, year: String, trackNo: Int) {
        val cv = ContentValues().apply {
            put("title", title); put("artist", artist); put("album", album)
            put("genre", genre); put("year", year); put("track_no", trackNo)
            put("last_modified", System.currentTimeMillis())
        }
        writableDatabase.update("tracks", cv, "id=?", arrayOf(id.toString()))
    }

    fun removeTrack(id: Long) {
        val db = writableDatabase
        transaction(db) {
            db.delete("tracks", "id=?", arrayOf(id.toString()))
            db.delete("playlist_tracks", "track_id=?", arrayOf(id.toString()))
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
}
