package com.musa.poetmusic

import android.app.Application
import com.musa.poetmusic.data.CacheGuard
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.server.PoetServer
import kotlin.concurrent.thread

class PoetApp : Application() {

    lateinit var db: MusicDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        // Runs before MainActivity creates the WebView, while its cache
        // directories are still safe to delete.
        CacheGuard.enforce(this)
        db = MusicDatabase(this)
        // Warm the database off the main thread so open-time maintenance
        // (WAL setup, one-off auto_vacuum migration) doesn't run during
        // the first UI read.
        thread(name = "db-warmup") { runCatching { db.writableDatabase } }
        PoetServer.start(this, db)
    }
}
