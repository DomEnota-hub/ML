package com.domenota.medialoader.data.storage

import org.junit.Assert.*
import org.junit.Test

class RecoveryTransactionTest {
    private class Crash : RuntimeException()
    private class Fixture : RecoveryJournal, RecoveryPorts {
        var record = RecoveryRecord("synthetic")
        var boundary = 0
        var crashAt = -1
        var broken = false
        var copied = false
        var valid = false
        var main = false
        var vk = false
        var catalog = false
        var playlists = false
        val downloadId = "unchanged-id"
        val relations = listOf("favorite", "album", "group", "source", "derived", "bulk-key")
        fun hit() { if (++boundary == crashAt) throw Crash() }
        override fun read() = record
        override fun write(record: RecoveryRecord) { hit(); this.record = record; hit() }
        override fun verifyIdentityAndBackup() { hit(); check(!broken); hit() }
        override fun copyPending() { hit(); copied = true; hit() }
        override fun verifyMetadataAndContent() { hit(); check(copied); valid = true; hit() }
        override fun switchMain(forward: Boolean) { hit(); check(!forward || valid); main = forward; hit() }
        override fun switchVk(forward: Boolean) { hit(); vk = forward; hit() }
        override fun switchCatalog(forward: Boolean) { hit(); catalog = forward; hit() }
        override fun switchPlaylists(forward: Boolean) { hit(); playlists = forward; hit() }
        override fun verifyReopened() { hit(); check(main && vk && catalog && playlists && valid); hit() }
        override fun verifyOriginalRetained() { hit(); check(!broken); hit() }
        override fun verifyRollback() { hit(); check(!main && !vk && !catalog && !playlists && !broken); hit() }
        fun engine() = RecoveryTransaction(this, this)
    }

    @Test fun resumeBeforeAndAfterEveryEffectAndJournalWrite() {
        val successful = Fixture().apply { engine().resume() }
        for (point in 1..successful.boundary) {
            val f = Fixture().apply { crashAt = point }
            try { f.engine().resume(); fail("No interruption at $point") } catch (_: Crash) { }
            f.crashAt = -1
            assertEquals(RecoveryStage.DONE, f.engine().resume().stage)
            assertTrue(f.main && f.vk && f.catalog && f.playlists)
            assertEquals("unchanged-id", f.downloadId)
            assertEquals(6, f.relations.size)
        }
    }

    @Test fun rollbackFromEveryInterruptedForwardBoundary() {
        val total = Fixture().apply { engine().resume() }.boundary
        for (point in 1..total) {
            val f = Fixture().apply { crashAt = point }
            try { f.engine().resume() } catch (_: Crash) { }
            f.crashAt = -1
            assertTrue(f.engine().rollback().rolledBack)
            f.verifyRollback()
            assertTrue(f.engine().rollback().rolledBack)
        }
    }

    @Test fun resumeRollbackBeforeAndAfterEveryCompensationAndWrite() {
        val baseline = Fixture().apply { engine().resume(); boundary = 0; engine().rollback() }
        for (point in 1..baseline.boundary) {
            val f = Fixture().apply { engine().resume(); boundary = 0; crashAt = point }
            try { f.engine().rollback(); fail("No rollback interruption $point") } catch (_: Crash) { }
            f.crashAt = -1
            assertTrue((if (f.record.rollingBack) f.engine().resume() else f.engine().rollback()).rolledBack)
            f.verifyRollback()
        }
    }

    @Test fun checksumOrIdentityFailureStopsBeforeCopyAndLinks() {
        val f = Fixture().apply { broken = true }
        try { f.engine().resume(); fail() } catch (_: IllegalStateException) { }
        assertFalse(f.copied || f.main || f.vk || f.catalog || f.playlists)
        assertEquals(RecoveryStage.PREPARED, f.record.stage)
    }
}
