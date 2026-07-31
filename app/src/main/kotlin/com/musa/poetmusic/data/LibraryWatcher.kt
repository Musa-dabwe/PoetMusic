package com.musa.poetmusic.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the library in step with the filesystem while the app is on screen.
 *
 * Two mechanisms, because neither alone is enough:
 *
 *  1. A [ContentObserver] on each mapped tree's children URI, with descendants
 *     included. When the document provider supports it (ExternalStorageProvider
 *     does) this is free and near-instant — no polling at all.
 *  2. A 60 s poll that re-lists each mapped folder's direct children and
 *     compares a cheap signature (names, sizes, modification times). This is
 *     the fallback for providers that never notify. It only ever reads
 *     directory entries — no file is opened and no tag is parsed — so a full
 *     scan is triggered only once the listing has actually changed.
 *
 * Both paths funnel into the same debounced trigger, so a burst of changes
 * (a file copy in progress, a bulk delete) causes one scan and not twenty.
 * Everything stops in [stop] when the app leaves the foreground.
 */
object LibraryWatcher {

    /** How often the fallback poll re-lists the mapped folders. */
    private const val POLL_SECONDS = 60L

    /** Quiet period a change must survive before a scan is triggered. */
    private const val DEBOUNCE_MS = 5_000L

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "poet-library-watcher").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var poll: ScheduledFuture<*>? = null
    private var pendingScan: ScheduledFuture<*>? = null
    private val observers = mutableListOf<ContentObserver>()

    /**
     * Last seen directory signature per mapped folder id. Replaced wholesale
     * rather than mutated, so the poll thread and stop() can't interleave.
     */
    @Volatile private var signatures: Map<Long, String> = emptyMap()

    /** Held so observers can be unregistered without a context argument. */
    @Volatile private var appContext: Context? = null

    @Volatile private var started = false

    @Synchronized
    fun start(context: Context, db: MusicDatabase) {
        if (started) return
        if (!LibraryScanner.autoScanEnabled(db)) return
        started = true
        val app = context.applicationContext
        appContext = app
        registerObservers(app, db)
        // Seed the signatures before the first poll so merely opening the app
        // never counts as a change.
        executor.execute { signatures = currentSignatures(app, db) }
        poll = executor.scheduleWithFixedDelay(
            { pollOnce(app, db) }, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS
        )
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        poll?.cancel(false)
        poll = null
        pendingScan?.cancel(false)
        pendingScan = null
        signatures = emptyMap()
        unregisterObservers()
    }

    /** Re-arm after the auto-scan setting is toggled in Settings. */
    fun restart(context: Context, db: MusicDatabase) {
        stop()
        start(context, db)
    }

    private fun registerObservers(app: Context, db: MusicDatabase) {
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = scheduleScan(app, db)
        }
        for (folder in db.folders()) {
            val tree = runCatching { Uri.parse(folder.treeUri) }.getOrNull() ?: continue
            val children = runCatching {
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree)
                )
            }.getOrNull() ?: continue
            // notifyForDescendants: a change deep inside an album folder must
            // reach us, not just one at the root.
            runCatching { app.contentResolver.registerContentObserver(children, true, observer) }
                .onSuccess { observers += observer }
        }
    }

    private fun unregisterObservers() {
        val resolver = appContext?.contentResolver
        observers.forEach { obs -> runCatching { resolver?.unregisterContentObserver(obs) } }
        observers.clear()
    }

    private fun pollOnce(app: Context, db: MusicDatabase) {
        if (LibraryScanner.isScanning) return
        val fresh = currentSignatures(app, db)
        if (fresh == signatures) return
        signatures = fresh
        scheduleScan(app, db)
    }

    /**
     * A cheap fingerprint of each mapped folder's direct children: entry names
     * with their sizes and modification times. New, removed and renamed files
     * all change it; playing a track does not.
     */
    private fun currentSignatures(app: Context, db: MusicDatabase): Map<Long, String> {
        val out = mutableMapOf<Long, String>()
        for (folder in db.folders()) {
            val tree = runCatching { Uri.parse(folder.treeUri) }.getOrNull() ?: continue
            val children = runCatching {
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree)
                )
            }.getOrNull() ?: continue
            val entries = sortedSetOf<String>()
            runCatching {
                app.contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(0) ?: ""
                        val size = if (c.isNull(1)) 0L else c.getLong(1)
                        val modified = if (c.isNull(2)) 0L else c.getLong(2)
                        entries += "$name:$size:$modified"
                    }
                }
            }
            out[folder.id] = entries.joinToString("|")
        }
        return out
    }

    /**
     * Trigger a scan once the changes settle. Repeated calls inside the
     * debounce window collapse into one — a file copy notifies continuously
     * while it is being written.
     */
    @Synchronized
    private fun scheduleScan(app: Context, db: MusicDatabase) {
        if (!started) return
        pendingScan?.cancel(false)
        pendingScan = executor.schedule({
            if (!LibraryScanner.isScanning) LibraryScanner.startScan(app, db)
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }
}
