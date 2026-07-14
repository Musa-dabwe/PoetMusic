package com.musa.poetmusic.data

import android.content.Context
import java.io.File
import kotlin.concurrent.thread

/**
 * Keeps the app's on-disk cache footprint bounded. WebView's Chromium HTTP
 * cache is the only part of the cache that grows without limit, so when the
 * total crosses [MAX_CACHE_BYTES] the WebView caches are wiped wholesale —
 * they are pure caches, so the only cost is one cold page load.
 *
 * Runs from [com.musa.poetmusic.PoetApp.onCreate], before MainActivity
 * instantiates the WebView, so the cache directories are safe to delete.
 */
object CacheGuard {

    private const val MAX_CACHE_BYTES = 50L * 1024 * 1024

    fun enforce(context: Context) {
        val app = context.applicationContext
        thread(name = "cache-guard") {
            runCatching {
                // Tag-editor scratch files are deleted after each edit; anything
                // still here is leftover from a crash mid-edit.
                File(app.cacheDir, "tagedit").deleteRecursively()

                val total = folderSize(app.cacheDir) + folderSize(app.codeCacheDir)
                if (total > MAX_CACHE_BYTES) {
                    File(app.cacheDir, "WebView").deleteRecursively()
                    File(app.codeCacheDir, "WebView").deleteRecursively()
                }
            }
        }
    }

    private fun folderSize(file: File): Long {
        if (!file.exists()) return 0
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { folderSize(it) } ?: 0
    }
}
