package com.musa.poetmusic.server

import android.content.res.AssetManager

/**
 * In-memory LRU for extracted album art, bounded by **bytes** rather than
 * entry count so a run of high-res covers can't balloon the heap.
 *
 * Also owns the branded placeholder cover served for tracks with no embedded
 * artwork, since that is the other half of "what /api/art/{id} responds with".
 */
object ArtCache {

    private const val MAX_BYTES = 12 * 1024 * 1024

    private var bytes = 0L
    private val entries = LinkedHashMap<Long, ByteArray>(16, 0.75f, true)

    /** Branded cover shown for tracks with no embedded artwork. */
    @Volatile var placeholder: ByteArray? = null
        private set

    fun loadPlaceholder(assets: AssetManager) {
        placeholder = runCatching {
            assets.open("web/placeholder.jpg").use { it.readBytes() }
        }.getOrNull()
    }

    fun get(id: Long): ByteArray? = synchronized(entries) { entries[id] }

    /** Drop a track's cached cover so the next request re-extracts it (after a tag edit). */
    fun evict(id: Long) = synchronized(entries) {
        entries.remove(id)?.let { bytes -= it.size }
        Unit
    }

    fun put(id: Long, art: ByteArray) = synchronized(entries) {
        // Oversized covers would evict everything else; serve them uncached.
        if (art.size > MAX_BYTES / 4) return@synchronized
        entries.remove(id)?.let { bytes -= it.size }
        entries[id] = art
        bytes += art.size
        val eldest = entries.entries.iterator()
        while (bytes > MAX_BYTES && eldest.hasNext()) {
            bytes -= eldest.next().value.size
            eldest.remove()
        }
    }
}
