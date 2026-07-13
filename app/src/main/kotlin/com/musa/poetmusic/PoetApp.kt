package com.musa.poetmusic

import android.app.Application
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.server.PoetServer

class PoetApp : Application() {

    lateinit var db: MusicDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        db = MusicDatabase(this)
        PoetServer.start(this, db)
    }
}
