package com.domenota.medialoader.data.diagnostics

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Keeps coroutine deadlines effective when Java networking ignores cancellation. */
internal object BoundedBlockingCall {
    private val executor = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
        LinkedBlockingQueue(8)) { task ->
        Thread(task, "diagnostic-http").apply { isDaemon = true }
    }

    suspend fun <T> run(onCancel: () -> Unit = {}, block: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            val future = try {
                executor.submit {
                    try {
                        val value = block()
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            } catch (error: Exception) {
                continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                onCancel()
                future.cancel(true)
                executor.purge()
            }
        }
}
