package com.domenota.medialoader.data.playback

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class WaveformDecodeGateTest {
    @Test fun onlyOneUncachedTrackDecodesAndWaitingCancellationDoesNotLeakPermit() {
        val gate = WaveformDecodeGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Int> { gate.decode { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); 1 } }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val second = pool.submit<Int> { gate.decode(cancelled::get) { error("Cancelled decode must never start") } }
            cancelled.set(true)
            try { second.get(1, TimeUnit.SECONDS); fail("Cancellation expected") }
            catch (e: ExecutionException) { assertTrue(e.cause is CancellationException) }
            release.countDown()
            assertEquals(1, first.get(1, TimeUnit.SECONDS))
            assertEquals(3, gate.decode { 3 })
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
