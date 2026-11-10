package com.domenota.medialoader.data.storage

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RecoverySafetyTest {
    @Test fun storageFailsClosedAtReserveAndOnOverflowOrInvalidSize() {
        val bytes = 1024L
        val exact = RecoverySafety.RESERVE + bytes * 4
        assertFalse(RecoverySafety.enoughSpace(bytes, exact, Long.MAX_VALUE))
        assertFalse(RecoverySafety.enoughSpace(bytes, Long.MAX_VALUE, exact))
        assertTrue(RecoverySafety.enoughSpace(bytes, exact + 1, exact + 1))
        for (size in listOf(-1L, 0L, Long.MAX_VALUE, 100L * 1024 * 1024 + 1))
            assertFalse(RecoverySafety.enoughSpace(size, Long.MAX_VALUE, Long.MAX_VALUE))
    }
    @Test fun ownerPathNameAndMimeMustAllMatchNoNameGuessing() {
        fun valid(owner: String? = "app", path: String? = RecoverySandbox.PATH,
            name: String? = "qa.jpg", mime: String? = "image/jpeg") = RecoverySafety.identity(owner, "app", path, name, "qa.jpg", mime, "image/jpeg")
        assertTrue(valid())
        assertFalse(valid(owner = null)); assertFalse(valid(owner = "other"))
        assertFalse(valid(path = "Pictures/MediaLoader/")); assertFalse(valid(name = "qa (1).jpg"))
        assertFalse(valid(mime = "image/png"))
    }
    @Test fun concurrentOperationRefusesWithoutWaitingAndLockReleasesOnFailure() {
        val directory = Files.createTempDirectory("ml-recovery-test").toFile()
        val file = java.io.File(directory, "lock")
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = Thread { RecoveryExclusive.run(file) { held.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        worker.start()
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS))
            try { RecoveryExclusive.run(file) { fail() }; fail() } catch (_: IllegalStateException) { }
        } finally { release.countDown(); worker.join(5000) }
        try { RecoveryExclusive.run(file) { throw IllegalArgumentException("injected") } } catch (_: IllegalArgumentException) { }
        assertEquals("available", RecoveryExclusive.run(file) { "available" })
        assertTrue(file.delete()); assertTrue(directory.delete())
    }
}
