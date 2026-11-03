package com.domenota.medialoader.core.logging

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One ordered writer. Overload drops records rather than blocking the UI or growing memory. */
internal class BoundedLogWriter(capacity: Int = 512) : AutoCloseable {
    val dropped = AtomicLong()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(capacity), { task ->
            Thread(task, "ML-log-writer").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy())
    fun offer(write: () -> Unit): Boolean = try {
        executor.execute { runCatching(write) }; true
    } catch (_: java.util.concurrent.RejectedExecutionException) { dropped.incrementAndGet(); false }
    fun flush(timeoutMs: Long = 2000): Boolean {
        val done = CountDownLatch(1)
        if (!offer { done.countDown() }) return false
        return try { done.await(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }
    override fun close() { executor.shutdown(); executor.awaitTermination(2, TimeUnit.SECONDS) }
}
