package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real v2 durable journal, restart of engine and injected service death at every phase.
 * The synthetic media ports do not touch any external Android files. */
class VisibilityV2CrashReplayTest {
    private val original=VisibilityFingerprint(13,"a".repeat(64))
    private val changed=VisibilityFingerprint(13,"b".repeat(64))
    private fun member()=VisibilityMoveRecord("move-1","folder","id-1",VisibilityDirection.HIDE,
        "content://media/external/images/media/1","image.jpg",1_700_000_000_000L,
        original,"operation-token")
    private fun batch()=VisibilityBatchRecord("batch-1","folder",VisibilityDirection.HIDE,listOf(member()))

    private class Ports(private val original:VisibilityFingerprint) : VisibilityMovePorts,VisibilityBatchPorts {
        val files=mutableMapOf("content://media/external/images/media/1" to original)
        var binding="content://media/external/images/media/1"
        val destination="file:///ml-hidden/token/image.jpg"
        var crashAt:VisibilityMovePhase?=null
        var unknownSource=false
        var deletions=0
        var receipt=false
        var committed=false
        override suspend fun preflight(batch:VisibilityBatchRecord) {}
        override suspend fun completed(member:VisibilityMoveRecord) =
            receipt && binding==destination &&
            !files.containsKey(member.sourceUri) && files[destination]==original
        override suspend fun commitVisibility(batch:VisibilityBatchRecord) {
            check(batch.members.all {completed(it)})
            committed=true
        }
        override suspend fun currentUri(record:VisibilityMoveRecord)=binding
        override suspend fun fingerprint(uri:String)=checkNotNull(files[uri])
        override suspend fun presence(uri:String) = if(uri.endsWith("/1") && unknownSource)
            VisibilityPresence.UNKNOWN else if(files.containsKey(uri)) VisibilityPresence.PRESENT else VisibilityPresence.MISSING
        override suspend fun allocate(record:VisibilityMoveRecord):String {
            if(crashAt==VisibilityMovePhase.PREPARED) throw IOException("simulated service kill before allocation")
            return destination
        }
        override suspend fun copyAndSync(record:VisibilityMoveRecord) {
            if(crashAt==VisibilityMovePhase.ALLOCATED) throw IOException("simulated full disk")
            files[destination]=checkNotNull(files[record.sourceUri])
        }
        override suspend fun verifyDestination(record:VisibilityMoveRecord) {
            check(record.destinationUri==destination && files[destination]==original)
        }
        override suspend fun rebind(record:VisibilityMoveRecord) {
            binding=destination
            if(crashAt==VisibilityMovePhase.VERIFIED) throw IOException("process died after DB CAS")
        }
        override suspend fun verifyBinding(record:VisibilityMoveRecord) {
            check(binding==destination)
        }
        override suspend fun removeSource(record:VisibilityMoveRecord) {
            if(crashAt==VisibilityMovePhase.LINKED && unknownSource)
                throw SecurityException("revoked permission")
            if(files.remove(record.sourceUri)!=null) deletions++
            if(crashAt==VisibilityMovePhase.LINKED) throw IOException("died after delete")
        }
        override suspend fun verifyFinalVisibility(record:VisibilityMoveRecord) {
            if(crashAt==VisibilityMovePhase.CLEANED) throw IOException("index interrupted")
            receipt=true
        }
    }
    private fun engine(file:File,ports:Ports):FolderVisibilityBatch {
        val store=VisibilityV2JournalStore(file)
        return FolderVisibilityBatch(store.batches,store.moves,FolderVisibilityMove(store.moves,ports),ports)
    }

    @Test fun eachDurablePhaseCanBeReplayedExactlyOnceAfterProcessDeath() = runBlocking {
        val root=Files.createTempDirectory("ml-v2-replay").toFile()
        try {
            for(phase in VisibilityMovePhase.values()) {
                val file=File(root,"journal-${phase.name}.bin")
                val ports=Ports(original);ports.crashAt=phase
                try {engine(file,ports).begin(batch());fail("Crash expected at $phase")}
                catch(_:IOException){}
                val pending=VisibilityV2JournalStore(file).snapshot()
                assertEquals(phase,pending.move?.phase)
                assertEquals(0,pending.batch?.next)
                assertTrue(VisibilityV2JournalStore.pending(file))
                assertEquals(if(phase==VisibilityMovePhase.LINKED || phase==VisibilityMovePhase.CLEANED) 1 else 0,ports.deletions)
                ports.crashAt=null
                engine(file,ports).resume()
                assertTrue(ports.committed)
                assertEquals(1,ports.deletions)
                assertFalse(ports.files.containsKey(member().sourceUri))
                assertEquals(original,ports.files[ports.destination])
                assertFalse(VisibilityV2JournalStore.pending(file))
                assertNull(VisibilityV2JournalStore(file).snapshot().batch)
                engine(file,ports).resume()
                assertEquals(1,ports.deletions)
            }
        }finally{root.deleteRecursively()}
    }

    @Test fun permissionRevocationAndSourceChangeDoNotDeleteUnverifiedOriginals() = runBlocking {
        val root=Files.createTempDirectory("ml-v2-guard").toFile()
        try {
            val file=File(root,"one.bin")
            val ports=Ports(original);ports.crashAt=VisibilityMovePhase.ALLOCATED
            try {engine(file,ports).begin(batch());fail("Expected no-space failure")}
            catch(_:IOException){}
            ports.crashAt=null
            ports.files[member().sourceUri]=changed
            assertTrue(runCatching{engine(file,ports).resume()}.isFailure)
            assertEquals(changed,ports.files[member().sourceUri])
            assertEquals(0,ports.deletions)
            assertEquals(VisibilityMovePhase.ALLOCATED,VisibilityV2JournalStore(file).moves.read()?.phase)
            ports.files[member().sourceUri]=original
            ports.unknownSource=true
            assertTrue(runCatching{engine(file,ports).resume()}.isFailure)
            assertEquals(original,ports.files[member().sourceUri])
            assertEquals(0,ports.deletions)
            ports.unknownSource=false
            engine(file,ports).resume()
            assertTrue(ports.committed)
            assertEquals(1,ports.deletions)
        }finally {root.deleteRecursively()}
    }
}
