package com.domenota.medialoader.data.catalog.visibility

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FolderVisibilityMoveTest {
    private val original = VisibilityFingerprint(12, "a".repeat(64))
    private val wrong = VisibilityFingerprint(12, "b".repeat(64))
    private fun plan(direction: VisibilityDirection = VisibilityDirection.HIDE) = VisibilityMoveRecord(
        "operation", "folder", "download", direction, "source", "photo.jpg", 123L, original, "unique-token")
    private class Journal : VisibilityMoveJournal {
        var record: VisibilityMoveRecord? = null
        override suspend fun read() = record
        override suspend fun replace(expected: VisibilityMoveRecord?, next: VisibilityMoveRecord?) {
            check(record == expected); record = next
        }
    }
    private inner class Ports : VisibilityMovePorts {
        var binding: String? = "source"
        val files = mutableMapOf("source" to original)
        var unknownSource = false
        var failCopy = false
        var corruptCopy = false
        var failAfterAllocation = false
        var failAfterBinding = false
        var failAfterRemoval = false
        var failIndex = false
        var failBindingProof = false
        var failPublishedMetadata = false
        var removalCount = 0
        var finalDirection: VisibilityDirection? = null
        override suspend fun currentUri(record: VisibilityMoveRecord) = binding
        override suspend fun fingerprint(uri: String) = files.getValue(uri)
        override suspend fun presence(uri: String) = if (uri == "source" && unknownSource) VisibilityPresence.UNKNOWN
            else if (uri in files) VisibilityPresence.PRESENT else VisibilityPresence.MISSING
        override suspend fun allocate(record: VisibilityMoveRecord): String {
            files.putIfAbsent("destination", wrong)
            if (failAfterAllocation) error("interrupted allocation")
            return "destination"
        }
        override suspend fun copyAndSync(record: VisibilityMoveRecord) {
            if (failCopy) error("disk full")
            files["destination"] = if (corruptCopy) wrong else files.getValue("source")
        }
        override suspend fun verifyDestination(record: VisibilityMoveRecord) {
            check(record.destinationUri == "destination")
            check(!failPublishedMetadata) { "MediaStore metadata unconfirmed" }
        }
        override suspend fun rebind(record: VisibilityMoveRecord) {
            check(binding == "source" || binding == "destination")
            binding = "destination"
            if (failAfterBinding) error("interrupted binding")
        }
        override suspend fun verifyBinding(record: VisibilityMoveRecord) {
            check(!failBindingProof) { "binding proof failed" }
            check(binding == "destination")
        }
        override suspend fun removeSource(record: VisibilityMoveRecord) {
            check(binding == "destination" && files["destination"] == original)
            removalCount++; files.remove("source")
            if (failAfterRemoval) error("interrupted removal")
        }
        override suspend fun verifyFinalVisibility(record: VisibilityMoveRecord) {
            if (failIndex) error("index still stale")
            finalDirection = record.direction
        }
    }
    private suspend fun fails(action: suspend () -> Unit) {
        try { action(); fail("Expected a rejected operation") } catch (_: IllegalStateException) { }
    }
    @Test fun hideAndShowVerifyBytesBeforeDeletion() = runBlocking {
        for (direction in VisibilityDirection.values()) {
            val j = Journal(); val p = Ports()
            FolderVisibilityMove(j,p).begin(plan(direction))
            assertNull(j.record); assertEquals("destination",p.binding)
            assertEquals(original,p.files["destination"]); assertFalse(p.files.containsKey("source"))
            assertEquals(1,p.removalCount); assertEquals(direction,p.finalDirection)
        }
    }
    @Test fun corruptedCopyNeverSwitchesOrDeletesOriginal() = runBlocking {
        val j=Journal(); val p=Ports(); p.corruptCopy=true
        fails { FolderVisibilityMove(j,p).begin(plan()) }
        assertEquals(VisibilityMovePhase.ALLOCATED,j.record?.phase)
        assertEquals("source",p.binding); assertEquals(original,p.files["source"]); assertEquals(0,p.removalCount)
        p.corruptCopy=false; FolderVisibilityMove(j,p).resume(); assertNull(j.record)
    }
    @Test fun publishedButUnconfirmedRestoreKeepsHiddenBytesAndJournalForResume() = runBlocking {
        val journal = Journal(); val ports = Ports()
        ports.failPublishedMetadata = true
        fails { FolderVisibilityMove(journal, ports).begin(plan(VisibilityDirection.SHOW)) }
        assertEquals(VisibilityMovePhase.ALLOCATED,journal.record?.phase)
        assertEquals(original,ports.files["source"])
        assertEquals(original,ports.files["destination"])
        assertEquals("source",ports.binding)
        assertEquals(0,ports.removalCount)
        // Once Android confirms the SAME exact destination, resume cannot
        // reallocate or overwrite another public media row.
        ports.failPublishedMetadata = false
        FolderVisibilityMove(journal,ports).resume()
        assertNull(journal.record)
        assertEquals(1,ports.removalCount)
        assertEquals(original,ports.files["destination"])
    }

    @Test fun diskFullRetainsOriginalAndCanResume() = runBlocking {
        val j=Journal(); val p=Ports(); p.failCopy=true
        fails { FolderVisibilityMove(j,p).begin(plan()) }
        assertEquals(original,p.files["source"]); assertEquals("source",p.binding)
        p.failCopy=false; FolderVisibilityMove(j,p).resume(); assertNull(j.record)
    }
    @Test fun processDeathAfterAllocationReusesSameDestination() = runBlocking {
        val j=Journal(); val p=Ports(); p.failAfterAllocation=true
        fails { FolderVisibilityMove(j,p).begin(plan()) }
        assertEquals(VisibilityMovePhase.PREPARED,j.record?.phase)
        p.failAfterAllocation=false; FolderVisibilityMove(j,p).resume()
        assertEquals(1,p.files.size); assertNull(j.record)
    }
    @Test fun engineStillRequiresFullBindingProofAfterRebindBeforeCleanup() = runBlocking {
        val journal = Journal(); val ports = Ports(); ports.failBindingProof = true
        fails { FolderVisibilityMove(journal,ports).begin(plan()) }
        // Rebinding the URI alone cannot commit LINKED or remove the source.
        assertEquals(VisibilityMovePhase.VERIFIED, journal.record?.phase)
        assertEquals(original, ports.files["source"])
        assertEquals(0, ports.removalCount)
        ports.failBindingProof = false
        FolderVisibilityMove(journal,ports).resume()
        assertNull(journal.record)
        assertEquals(1, ports.removalCount)
    }

    @Test fun processDeathAfterBindingReplaysWithoutDeletingOriginalEarly() = runBlocking {
        val j=Journal(); val p=Ports(); p.failAfterBinding=true
        fails { FolderVisibilityMove(j,p).begin(plan()) }
        assertEquals(VisibilityMovePhase.VERIFIED,j.record?.phase); assertEquals(original,p.files["source"])
        p.failAfterBinding=false; FolderVisibilityMove(j,p).resume(); assertNull(j.record); assertEquals(1,p.removalCount)
    }
    @Test fun processDeathAfterRemovalDoesNotDeleteAgain() = runBlocking {
        val j=Journal(); val p=Ports(); p.failAfterRemoval=true
        fails { FolderVisibilityMove(j,p).begin(plan()) }
        assertEquals(VisibilityMovePhase.LINKED,j.record?.phase); assertEquals(original,p.files["destination"])
        p.failAfterRemoval=false; FolderVisibilityMove(j,p).resume(); assertNull(j.record); assertEquals(1,p.removalCount)
    }
    @Test fun permissionRevocationNeverCountsAsMissing() = runBlocking {
        val j=Journal(); val p=Ports(); p.failAfterBinding=true
        fails { FolderVisibilityMove(j,p).begin(plan()) }
        p.failAfterBinding=false; p.unknownSource=true
        fails { FolderVisibilityMove(j,p).resume() }
        assertEquals(original,p.files["source"]); assertEquals(0,p.removalCount)
    }
    @Test fun changedSourceOrBindingBlocksCleanup() = runBlocking {
        val j=Journal(); val p=Ports(); p.failAfterBinding=true
        fails { FolderVisibilityMove(j,p).begin(plan()) }
        p.failAfterBinding=false; p.files["source"]=wrong
        fails { FolderVisibilityMove(j,p).resume() }; assertEquals(0,p.removalCount)
        p.files["source"]=original; p.binding="unrelated"
        fails { FolderVisibilityMove(j,p).resume() }; assertEquals(0,p.removalCount)
    }
    @Test fun staleSystemIndexKeepsJournalUntilConfirmed() = runBlocking {
        val j=Journal(); val p=Ports(); p.failIndex=true
        fails { FolderVisibilityMove(j,p).begin(plan()) }
        assertEquals(VisibilityMovePhase.CLEANED,j.record?.phase); assertEquals(original,p.files["destination"])
        p.failIndex=false; FolderVisibilityMove(j,p).resume(); assertNull(j.record); assertEquals(1,p.removalCount)
    }
    @Test fun pendingJournalBlocksSecondOperation() = runBlocking {
        val j=Journal(); val p=Ports(); p.failCopy=true
        fails { FolderVisibilityMove(j,p).begin(plan()) }
        fails { FolderVisibilityMove(j,p).begin(plan().copy(operationId="second")) }
        assertEquals("operation",j.record?.operationId); assertEquals(original,p.files["source"])
    }
}
