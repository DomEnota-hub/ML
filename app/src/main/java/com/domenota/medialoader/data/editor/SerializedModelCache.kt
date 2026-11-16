package com.domenota.medialoader.data.editor

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native handle ownership. The key is read under the same lock as loading,
 * inference, replacement and eviction. No handle is closed during a run. */
internal class SerializedModelCache<T : AutoCloseable> {
    private val gate = Mutex()
    private var handle: T? = null
    private var identity: String? = null

    suspend fun <R> use(key: () -> String, open: () -> T, block: suspend (T, Boolean) -> R): R {
        gate.lock()
        try {
            currentCoroutineContext().ensureActive()
            val wanted = key()
            val previous = handle
            val reused = previous != null && identity == wanted
            val value = if (reused) checkNotNull(previous) else {
                closeLocked()
                open().also { handle = it; identity = wanted }
            }
            // A native init is not interruptible. Do not begin inference on a
            // cancelled request when init returns. A valid handle may be reused.
            currentCoroutineContext().ensureActive()
            return block(value, reused)
        } finally { gate.unlock() }
    }

    suspend fun releaseIf(predicate: () -> Boolean): Boolean = gate.withLock {
        if (!predicate()) false else closeLocked()
    }

    suspend fun <R> mutate(block: () -> R): R = gate.withLock {
        closeLocked()
        block()
    }

    private fun closeLocked(): Boolean {
        val previous = handle ?: return false
        handle = null
        identity = null
        previous.close()
        return true
    }
}

/** Grace is optional and bounded; memory pressure always wins. */
internal object LaMaIdlePolicy {
    const val GRACE_MS = 20_000L
    private const val MIN_AVAILABLE_BYTES = 512L * 1024 * 1024
    fun graceMillis(lowMemory: Boolean, available: Long, threshold: Long): Long =
        if (!lowMemory && available >= MIN_AVAILABLE_BYTES && threshold >= 0 &&
            available / 2 >= threshold) GRACE_MS else 0L
    fun mayRelease(activeEditors: Int, currentGeneration: Long, scheduledGeneration: Long) =
        activeEditors == 0 && currentGeneration == scheduledGeneration
}
