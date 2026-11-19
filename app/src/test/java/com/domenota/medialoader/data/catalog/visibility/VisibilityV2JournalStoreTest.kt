package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** No Android mutation: exact durable checkpoints and crashes on JVM temporary files. */
class VisibilityV2JournalStoreTest {
    private val hash="a".repeat(64)
    private fun member(index:Int)=VisibilityMoveRecord(
        "op-$index","folder","media-$index",VisibilityDirection.HIDE,
        "content://media/external_primary/images/media/$index","image-$index.jpg",
        1_700_000_000_000L,VisibilityFingerprint(100L+index,hash),"token-$index",
    )
    private fun batch(count:Int)=VisibilityBatchRecord("batch-$count","folder",VisibilityDirection.HIDE,
        (1..count).map(::member))
    private fun withFile(run:suspend (File)->Unit)=runBlocking {
        val root=Files.createTempDirectory("ml-v2-journal").toFile()
        try { run(File(root,"checkpoint-v2.bin")) } finally { root.deleteRecursively() }
    }

    @Test fun exact1_8_46MemberPlansRemainRecoverableAfterRecreation() = withFile { file ->
        for(count in listOf(1,8,46)) {
            file.delete()
            val plan=batch(count)
            val store=VisibilityV2JournalStore(file)
            assertFalse(VisibilityV2JournalStore.pending(file))
            store.batches.replace(null,plan)
            assertTrue(VisibilityV2JournalStore.pending(file))
            assertEquals(plan,VisibilityV2JournalStore(file).batches.read())
            val first=plan.members.first()
            store.moves.replace(null,first)
            val phases=listOf(VisibilityMovePhase.ALLOCATED,VisibilityMovePhase.VERIFIED,
                VisibilityMovePhase.LINKED,VisibilityMovePhase.CLEANED)
            var prior=first
            for(phase in phases) {
                val later=first.copy(phase=phase,destinationUri="file:///hidden/target")
                store.moves.replace(prior,later)
                val recovered=VisibilityV2JournalStore(file).snapshot()
                assertEquals(later,recovered.move)
                assertEquals(plan,recovered.batch)
                assertEquals(phase,VisibilityRecoveryPlanner.inspect(recovered.batch,recovered.move)?.active?.phase)
                prior=later
            }
            store.moves.replace(prior,null)
            store.batches.replace(plan,plan.copy(next=1))
            val recovered=VisibilityV2JournalStore(file).snapshot()
            assertEquals(1,recovered.batch?.next)
            assertNull(recovered.move)
            store.batches.replace(plan.copy(next=1),null)
            assertFalse(VisibilityV2JournalStore.pending(file))
            assertNull(VisibilityV2JournalStore(file).batches.read())
        }
    }

    @Test fun writingFaultBeforeRenamePreservesOldCompleteState() = withFile { file ->
        val plan=batch(8)
        val store=VisibilityV2JournalStore(file)
        store.batches.replace(null,plan)
        val before=file.readBytes()
        val broken=VisibilityV2JournalStore(file) { if(it.move!=null) VisibilityV2WriteFault.BEFORE_RENAME
            else VisibilityV2WriteFault.NONE }
        try { broken.moves.replace(null,plan.members.first());fail("Expected simulated crash") }
        catch(_:java.io.IOException) { }
        assertArrayEquals(before,file.readBytes())
        assertEquals(plan,VisibilityV2JournalStore(file).snapshot().batch)
        assertNull(VisibilityV2JournalStore(file).snapshot().move)
    }

    @Test fun writingFaultAfterRenameKeepsNewWholeCheckpointForExplicitResume() = withFile { file ->
        val plan=batch(8)
        VisibilityV2JournalStore(file).batches.replace(null,plan)
        val broken=VisibilityV2JournalStore(file) { if(it.move!=null) VisibilityV2WriteFault.AFTER_RENAME
            else VisibilityV2WriteFault.NONE }
        try { broken.moves.replace(null,plan.members.first());fail("Expected simulated crash") }
        catch(_:java.io.IOException) { }
        val reopened=VisibilityV2JournalStore(file)
        assertEquals(plan.members.first(),reopened.moves.read())
        assertEquals(plan,reopened.batches.read())
        assertTrue(VisibilityV2JournalStore.pending(file))
    }

    @Test fun corruptedOrWrongVersionJournalBlocksNewWork() = withFile { file ->
        val store=VisibilityV2JournalStore(file)
        val plan=batch(1)
        store.batches.replace(null,plan)
        val bytes=file.readBytes();bytes[11]=(bytes[11].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertTrue(VisibilityV2JournalStore.pending(file))
        assertTrue(runCatching { store.batches.read() }.isFailure)
        assertArrayEquals(bytes,file.readBytes())
        assertTrue(runCatching { store.batches.replace(null,plan) }.isFailure)
    }

    @Test fun rejectMismatchedActiveMemberDuplicateSourceAndOrphan() = withFile { file ->
        val plan=batch(8)
        val store=VisibilityV2JournalStore(file)
        store.batches.replace(null,plan)
        assertTrue(runCatching { store.moves.replace(null,plan.members[1]) }.isFailure)
        assertNull(store.moves.read())
        assertTrue(runCatching { store.batches.replace(plan,plan.copy(
            members=plan.members.mapIndexed { i,r -> if(i==4) r.copy(sourceUri=plan.members[0].sourceUri) else r }
        )) }.isFailure)
        assertEquals(plan,store.batches.read())
        assertTrue(runCatching { VisibilityV2Checkpoint(9,null,plan.members[0]) }.isFailure)
    }

    @Test fun v1AndV2AreIndependentAndConflictingPendingBlocksWork() = withFile { file ->
        val plan=batch(1)
        val old=FileVisibilityBatchJournal(File(file.parentFile,"batch.bin"))
        old.replace(null,plan)
        val oldBytes=File(file.parentFile,"batch.bin").readBytes()
        val store=VisibilityV2JournalStore(file)
        store.batches.replace(null,plan)
        assertTrue(runCatching {visibilityJournalFamily(true,true,true)}.isFailure)
        assertTrue(runCatching {visibilityJournalFamily(false,true,true)}.isFailure)
        assertEquals(VisibilityJournalFamily.LEGACY_V1,visibilityJournalFamily(false,true,false))
        assertEquals(VisibilityJournalFamily.VERSIONED_V2,visibilityJournalFamily(false,false,true))
        assertEquals(VisibilityJournalFamily.VERSIONED_V2,visibilityJournalFamily(true,false,false))
        assertArrayEquals(oldBytes,File(file.parentFile,"batch.bin").readBytes())
        assertEquals(plan,old.read())
    }

    @Test fun staleCasNeverOverwritesCommittedCheckpoint() = withFile { file ->
        val plan=batch(1)
        val store=VisibilityV2JournalStore(file)
        store.batches.replace(null,plan)
        assertTrue(runCatching {store.batches.replace(null,plan)}.isFailure)
        val first=plan.members.first()
        store.moves.replace(null,first)
        assertTrue(runCatching {store.moves.replace(null,first)}.isFailure)
        assertEquals(first,VisibilityV2JournalStore(file).moves.read())
    }

    @Test fun invalidVersionAndOversizeArePendingWithoutRewriting() = withFile { file ->
        val empty=VisibilityV2CheckpointCodec.encode(VisibilityV2Checkpoint(0,null,null))
        file.writeBytes(empty.copyOf().also {it[7]=99})
        assertTrue(VisibilityV2JournalStore.pending(file))
        assertTrue(runCatching {VisibilityV2JournalStore(file).snapshot()}.isFailure)
        file.writeBytes(ByteArray(VisibilityV2CheckpointCodec.MAX_BYTES+1))
        assertTrue(VisibilityV2JournalStore.pending(file))
        assertTrue(runCatching {VisibilityV2JournalStore(file).snapshot()}.isFailure)
    }
}
