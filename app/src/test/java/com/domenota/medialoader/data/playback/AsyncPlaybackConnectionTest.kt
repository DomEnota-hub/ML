package com.domenota.medialoader.data.playback

import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AsyncPlaybackConnectionTest {
    @Test fun blockingBinderConstructionRunsOnWorkerAndCancelledResultReleasesOnApplicationThread() {
        Executors.newSingleThreadExecutor { Thread(it, "connection-worker") }.asCoroutineDispatcher().use { worker ->
            Executors.newSingleThreadExecutor { Thread(it, "connection-application") }.asCoroutineDispatcher().use { application ->
                val started = CountDownLatch(1)
                val finishBind = CountDownLatch(1)
                val released = CountDownLatch(1)
                val inner = SettableFuture.create<String>()
                var builderThread = ""
                var releaseThread = ""
                val future = asyncPlaybackConnection(worker, application, release = {
                    releaseThread = Thread.currentThread().name
                    it.cancel(false)
                    released.countDown()
                }) {
                    builderThread = Thread.currentThread().name
                    started.countDown()
                    check(finishBind.await(10, TimeUnit.SECONDS))
                    inner
                }
                try {
                    assertTrue(started.await(10, TimeUnit.SECONDS))
                    assertEquals("connection-worker", builderThread)
                    assertFalse(future.isDone)
                    future.cancel(false)
                } finally { finishBind.countDown() }
                assertTrue(released.await(10, TimeUnit.SECONDS))
                assertEquals("connection-application", releaseThread)
                assertTrue(inner.isCancelled)
            }
        }
    }
    @Test fun successfulConnectionIsDeliveredAndBuildFailureRemainsVisible() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { application ->
                val future = asyncPlaybackConnection(worker, application, release = { error("unexpected release") }) {
                    SettableFuture.create<String>().apply { set("connected") }
                }
                assertEquals("connected", future.get(10, TimeUnit.SECONDS))
                val failed = asyncPlaybackConnection<String>(worker, application, release = {}) {
                    throw IllegalStateException("synthetic-bind-failure")
                }
                try { failed.get(10, TimeUnit.SECONDS); fail("must retain failure") }
                catch (e: java.util.concurrent.ExecutionException) { assertEquals("synthetic-bind-failure", e.cause?.message) }
            }
        }
    }
}
