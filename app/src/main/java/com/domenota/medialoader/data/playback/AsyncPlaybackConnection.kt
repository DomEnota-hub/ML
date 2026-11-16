package com.domenota.medialoader.data.playback

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*

/** Bridges worker-side synchronous connection creation and application-thread future ownership. */
internal fun <T> asyncPlaybackConnection(
    worker: CoroutineDispatcher = Dispatchers.IO,
    application: CoroutineDispatcher = Dispatchers.Main.immediate,
    release: (ListenableFuture<T>) -> Unit,
    build: () -> ListenableFuture<T>,
): ListenableFuture<T> {
    val result = SettableFuture.create<T>()
    CoroutineScope(SupervisorJob() + worker).launch {
        try {
            val pending = build()
            withContext(NonCancellable + application) {
                if (result.isCancelled) release(pending)
                else result.setFuture(pending)
            }
        } catch (error: Exception) { result.setException(error) }
    }
    return result
}
