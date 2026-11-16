package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FolderVisibilityBatchTest {
    private fun plan() = VisibilityBatchRecord("batch", "folder", VisibilityDirection.HIDE, (1..3).map {
        VisibilityMoveRecord("op$it", "folder", "id$it", VisibilityDirection.HIDE, "source$it", "same.jpg", 123L,
            VisibilityFingerprint(10L, "a".repeat(64)), "token$it")
    })
    private class Fixture(val root: File, val batch: VisibilityBatchRecord) : VisibilityMovePorts, VisibilityBatchPorts {
        val moveJournal = FileVisibilityMoveJournal(File(root,"move"))
        val batchJournal = FileVisibilityBatchJournal(File(root,"batch"))
        val bindings = batch.members.associate { it.downloadId to it.sourceUri }.toMutableMap()
        val files = batch.members.associate { it.sourceUri to it.fingerprint }.toMutableMap()
        val receipts = mutableSetOf<String>()
        val deleted = mutableListOf<String>()
        var failMember: String? = null; var failPreflight = false; var failCommit = false; var committed = false
        override suspend fun preflight(batch: VisibilityBatchRecord) { check(!failPreflight) }
        override suspend fun completed(member: VisibilityMoveRecord) = member.operationId in receipts &&
            bindings[member.downloadId] == "destination-${member.operationId}" && member.sourceUri !in files
        override suspend fun commitVisibility(batch: VisibilityBatchRecord) {
            check(batch.members.all { completed(it) }); committed = true
            if (failCommit) error("interrupted after final flag")
        }
        override suspend fun currentUri(record: VisibilityMoveRecord) = bindings[record.downloadId]
        override suspend fun fingerprint(uri: String) = files.getValue(uri)
        override suspend fun presence(uri: String) = if (uri in files) VisibilityPresence.PRESENT else VisibilityPresence.MISSING
        override suspend fun allocate(record: VisibilityMoveRecord) = "destination-${record.operationId}"
        override suspend fun copyAndSync(record: VisibilityMoveRecord) {
            check(record.operationId != failMember); files[record.destinationUri!!] = files.getValue(record.sourceUri)
        }
        override suspend fun verifyDestination(record: VisibilityMoveRecord) { check(record.destinationUri == "destination-${record.operationId}") }
        override suspend fun rebind(record: VisibilityMoveRecord) { bindings[record.downloadId] = record.destinationUri!! }
        override suspend fun verifyBinding(record: VisibilityMoveRecord) { check(bindings[record.downloadId] == record.destinationUri) }
        override suspend fun removeSource(record: VisibilityMoveRecord) { deleted += record.sourceUri; files.remove(record.sourceUri) }
        override suspend fun verifyFinalVisibility(record: VisibilityMoveRecord) { receipts += record.operationId }
        fun engine() = FolderVisibilityBatch(batchJournal,moveJournal,FolderVisibilityMove(moveJournal,this),this)
    }
    private suspend fun fixture(action: suspend (Fixture) -> Unit) {
        val root = Files.createTempDirectory("ml-visibility-batch").toFile()
        try { action(Fixture(root,plan())) } finally { root.deleteRecursively() }
    }
    private suspend fun fails(action: suspend () -> Unit) {
        try { action(); fail("Expected rejection") } catch (_: IllegalStateException) { }
    }
    @Test fun failedPreflightDoesNotMoveAnything() = runBlocking {
        fixture { f -> f.failPreflight = true; fails { f.engine().begin(f.batch) }
            assertNull(f.batchJournal.read()); assertNull(f.moveJournal.read()); assertTrue(f.deleted.isEmpty()); assertFalse(f.committed) }
    }
    @Test fun interruptedMemberResumesWithoutRepeatingCompletedMembers() = runBlocking {
        fixture { f -> f.failMember = "op2"; fails { f.engine().begin(f.batch) }
            assertEquals(1,f.batchJournal.read()?.next); assertEquals("op2",f.moveJournal.read()?.operationId)
            assertEquals(listOf("source1"),f.deleted); assertFalse(f.committed)
            f.failMember = null; f.engine().resume(); assertTrue(f.committed); assertNull(f.batchJournal.read())
            assertEquals(listOf("source1","source2","source3"),f.deleted) }
    }
    @Test fun durable46MemberPlanResumesAcrossEngineRecreationWithoutDuplicateRemoval() = runBlocking {
        val root = Files.createTempDirectory("ml-visibility-46").toFile()
        try {
            val template = plan().members.first()
            val members = (1..46).map { index -> template.copy(operationId = "op$index", downloadId = "id$index",
                sourceUri = "source$index", destinationToken = "token$index") }
            val batch = VisibilityBatchRecord("batch-46", "folder", VisibilityDirection.HIDE, members)
            val f = Fixture(root, batch)
            f.failMember = "op24"
            fails { f.engine().begin(batch) }
            val durableBatch = FileVisibilityBatchJournal(File(root, "batch")).read()!!
            val durableMove = FileVisibilityMoveJournal(File(root, "move")).read()!!
            assertEquals(23, durableBatch.next)
            assertEquals(members, durableBatch.members)
            assertEquals("op24", durableMove.operationId)
            assertEquals(VisibilityMovePhase.ALLOCATED, durableMove.phase)
            assertEquals(members.take(23).map { it.sourceUri }, f.deleted)
            assertTrue(members.drop(23).all { it.sourceUri in f.files })
            assertFalse(f.committed)
            f.failMember = null
            // New journals and engine model a restart; the fixture retains durable storage/catalog effects.
            fun restarted() = FolderVisibilityBatch(FileVisibilityBatchJournal(File(root, "batch")),
                FileVisibilityMoveJournal(File(root, "move")),
                FolderVisibilityMove(FileVisibilityMoveJournal(File(root, "move")), f), f)
            restarted().resume()
            assertTrue(f.committed)
            assertEquals(members.map { it.sourceUri }, f.deleted)
            assertEquals(46, f.deleted.toSet().size)
            assertEquals(46, f.receipts.size)
            assertTrue(members.all { f.bindings[it.downloadId] == "destination-${it.operationId}" &&
                f.files["destination-${it.operationId}"] == it.fingerprint && it.sourceUri !in f.files })
            assertNull(f.batchJournal.read()); assertNull(f.moveJournal.read())
            restarted().resume()
            assertEquals(46, f.deleted.size)
        } finally { root.deleteRecursively() }
    }
    @Test fun interruptedFinalCommitDoesNotToggleOrMoveTwice() = runBlocking {
        fixture { f -> f.failCommit = true; fails { f.engine().begin(f.batch) }
            assertEquals(3,f.batchJournal.read()?.next); assertTrue(f.committed); assertNull(f.moveJournal.read())
            f.failCommit = false; f.engine().resume(); assertTrue(f.committed); assertEquals(3,f.deleted.size); assertNull(f.batchJournal.read()) }
    }
    @Test fun receiptRecoversCrashBeforeBatchCursorAdvance() = runBlocking {
        fixture { f -> f.batchJournal.replace(null,f.batch)
            FolderVisibilityMove(f.moveJournal,f).begin(f.batch.members.first())
            assertEquals(0,f.batchJournal.read()?.next); assertNull(f.moveJournal.read())
            f.engine().resume(); assertEquals(listOf("source1","source2","source3"),f.deleted); assertTrue(f.committed) }
    }
    @Test fun foreignPendingMoveCannotBeResumedByBatch() = runBlocking {
        fixture { f -> f.batchJournal.replace(null,f.batch); f.moveJournal.replace(null,f.batch.members[1])
            fails { f.engine().resume() }; assertTrue(f.deleted.isEmpty()); assertEquals("op2",f.moveJournal.read()?.operationId) }
    }
    @Test fun corruptBatchIsNotTreatedAsAnEmptyPlan() = runBlocking {
        fixture { f -> val file = File(f.root,"batch"); f.batchJournal.replace(null,f.batch)
            assertEquals(f.batch, FileVisibilityBatchJournal(file).read()); val raw=file.readBytes(); raw[10]=(raw[10]+1).toByte(); file.writeBytes(raw)
            fails { f.engine().begin(f.batch) }; assertTrue(raw.contentEquals(file.readBytes())); assertTrue(f.deleted.isEmpty()) }
    }

    @Test fun durableMembershipPreparationResumesAfterInterruption() = runBlocking {
        val root=Files.createTempDirectory("ml-membership").toFile()
        try {
            val member=plan().members.first()
            val change=VisibilityMemberChange(member.downloadId,true,null,member.folderId,member.sourceUri,true,null)
            val batch=plan().copy(members=listOf(member),membership=change)
            val fixture=Fixture(root,batch)
            val observed=mutableListOf<VisibilityMemberChange>()
            var interrupt=true
            val ports=object:VisibilityBatchPorts by fixture {
                override suspend fun prepareMembership(batch:VisibilityBatchRecord,pending:VisibilityMoveRecord?) {
                    assertEquals(batch,fixture.batchJournal.read())
                    observed+=checkNotNull(batch.membership)
                    if(interrupt) error("interrupted after journal")
                }
            }
            fun engine()=FolderVisibilityBatch(fixture.batchJournal,fixture.moveJournal,FolderVisibilityMove(fixture.moveJournal,fixture),ports)
            fails { engine().begin(batch) }
            assertEquals(batch,fixture.batchJournal.read());assertTrue(fixture.deleted.isEmpty())
            interrupt=false;engine().resume()
            assertEquals(listOf(change,change),observed);assertEquals(listOf(member.sourceUri),fixture.deleted);assertNull(fixture.batchJournal.read())
        } finally { root.deleteRecursively() }
    }
    @Test fun membershipCodecPreservesExternalSeedAndPriorReceipt() {
        val member=plan().members.first()
        val receipt=member.copy(phase=VisibilityMovePhase.CLEANED,destinationUri="file:///hidden.jpg")
        val change=VisibilityMemberChange(member.downloadId,false,member.folderId,"second",requireNotNull(receipt.destinationUri),true,"{\"name\":\"файл\"}",receipt)
        val batch=plan().copy(members=emptyList(),membership=change)
        assertEquals(batch,VisibilityBatchCodec.decode(VisibilityBatchCodec.encode(batch)))
    }
    @Test fun emptyMembershipBatchDoesNotCopyOrDeleteBytes() = runBlocking {
        val root=Files.createTempDirectory("ml-membership-metadata").toFile()
        try {
            val member=plan().members.first()
            val receipt=member.copy(phase=VisibilityMovePhase.CLEANED,destinationUri="file:///hidden.jpg")
            val change=VisibilityMemberChange(member.downloadId,true,member.folderId,"second",requireNotNull(receipt.destinationUri),false,null,receipt)
            val batch=plan().copy(members=emptyList(),membership=change)
            val fixture=Fixture(root,batch)
            fixture.engine().begin(batch)
            assertTrue(fixture.committed);assertTrue(fixture.deleted.isEmpty());assertNull(fixture.batchJournal.read())
        } finally { root.deleteRecursively() }
    }
    @Test fun versionOneBatchRemainsReadable() {
        val batch=plan()
        val current=VisibilityBatchCodec.encode(batch)
        val payload=current.copyOfRange(0,current.size-33)
        payload[7]=1
        val old=payload+java.security.MessageDigest.getInstance("SHA-256").digest(payload)
        assertEquals(batch,VisibilityBatchCodec.decode(old))
    }
}
