package com.domenota.medialoader.core.logging

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedLogWriterTest {
    @Test fun slowStorageDoesNotBlockCallerAndQueueOverflowIsBounded() {
        val writer = BoundedLogWriter(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = Thread.currentThread()
        val rows = mutableListOf<Int>()
        try {
            assertTrue(writer.offer {
                assertNotSame(caller, Thread.currentThread())
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                rows.add(1)
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(writer.offer { rows.add(2) })
            assertFalse(writer.offer { rows.add(3) })
            assertEquals(1, writer.dropped.get())
            release.countDown()
            // The queue can briefly still be full, so wait for the queued write directly.
            writer.close()
            assertEquals(listOf(1, 2), rows)
        } finally { release.countDown(); writer.close() }
    }
    @Test fun flushIncludesEarlierWritesAndWriterSurvivesStorageFailure() {
        BoundedLogWriter().use { writer ->
            val rows = mutableListOf<Int>()
            writer.offer { throw java.io.IOException("unavailable storage") }
            writer.offer { rows.add(1) }
            writer.offer { rows.add(2) }
            assertTrue(writer.flush())
            assertEquals(listOf(1, 2), rows)
        }
    }
}
