package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.util.LruCache
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Session-owned cache: absolute source timestamps survive trim/undo; UI references stay valid. */
internal class VideoSessionFrameCache(maxBytes: Int = 8 * 1024 * 1024) : AutoCloseable {
    data class Key(val source: String, val revision: String, val timeMs: Long, val width: Int, val height: Int)
    private val entries = object : LruCache<Key, Bitmap>(maxBytes) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }
    private var closed = false
    @Synchronized fun get(key: Key): Bitmap? = if (closed) null else entries.get(key)
    @Synchronized fun put(key: Key, bitmap: Bitmap) { if (!closed) entries.put(key, bitmap) }
    @Synchronized fun bytes() = entries.size()
    @Synchronized override fun close() { closed = true; entries.evictAll() }
    // Eviction never recycles bitmaps still drawn by Compose; normal bitmap lifetime owns them.
}

/** One native frame extraction at a time, fair between a thumbnail batch and boundary requests. */
internal object VideoFrameDecodeBudget {
    private val gate = Semaphore(1, true)
    fun <T> decode(cancelled: () -> Boolean = { false }, block: () -> T): T {
        while (!gate.tryAcquire(40, TimeUnit.MILLISECONDS)) {
            if (cancelled()) throw java.util.concurrent.CancellationException()
        }
        try {
            if (cancelled()) throw java.util.concurrent.CancellationException()
            return block()
        } finally { gate.release() }
    }
}
