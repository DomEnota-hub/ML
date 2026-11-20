package com.domenota.medialoader.data.catalog.visibility

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SelectiveArchiveMoveEngineTest {
    private val hash="a".repeat(64)
    private val links="b".repeat(64)
    private val token="Q".repeat(32)
    private val original=SelectiveArchiveIdentity(
        "track-7","content://media/external/audio/media/77",77L,
        "external_primary","Music/Shared/","original.mp3",1234L,
        hash,1_700_000_000_000L,links)
    private val snapshot=SelectiveArchiveCheckpoint("virtual-album",listOf(original),1_700_000_000_000L)
    private val target="Music/MediaLoaderArchive/$token/"

    private class Port(private val original:SelectiveArchiveIdentity):SelectiveArchiveMovePort {
        var pos=SelectiveArchiveLocation.SOURCE
        var marker=false
        var public:Boolean?=true
        var links:Boolean?=true
        var id=original.mediaStoreId
        var bytes=original.sizeBytes
        var failAfterMove=false
        var targetIsForeign=false
        var moveCount=0
        var sourceTaken=false
        override suspend fun observe(original:SelectiveArchiveIdentity,
            receipt:SelectiveArchiveMoveReceipt):SelectiveArchiveMoveObservation {
            val record=if(pos==SelectiveArchiveLocation.UNKNOWN)null else original.copy(
                relativePath=if(pos==SelectiveArchiveLocation.SOURCE)
                    original.relativePath else receipt.targetRelativePath,
                mediaStoreId=id,sizeBytes=bytes)
            return SelectiveArchiveMoveObservation(
                pos,record,public,links,
                if(pos==SelectiveArchiveLocation.HIDDEN) marker && !targetIsForeign else null)
        }
        override suspend fun ensureTarget(original:SelectiveArchiveIdentity,
            receipt:SelectiveArchiveMoveReceipt) {
            check(!targetIsForeign) { "Foreign marker" }
            marker=true
        }
        override suspend fun changeRelativePath(original:SelectiveArchiveIdentity,
            receipt:SelectiveArchiveMoveReceipt,toHidden:Boolean) {
            check(pos==if(toHidden) SelectiveArchiveLocation.SOURCE else SelectiveArchiveLocation.HIDDEN)
            check(marker && !targetIsForeign)
            check(!(!toHidden && sourceTaken)) { "Original destination already occupied" }
            pos=if(toHidden)SelectiveArchiveLocation.HIDDEN else SelectiveArchiveLocation.SOURCE
            public=!toHidden
            moveCount++
            if(failAfterMove) {
                failAfterMove=false
                error("Simulated process interruption after MediaStore move")
            }
        }
    }

    @Test fun originalIsRestoredBySameUriAndAllReferences()=runBlocking<Unit> {
        val dir=Files.createTempDirectory("archive-engine").toFile()
        try {
            val port=Port(original);val journal=SelectiveArchiveMoveJournal(dir)
            val engine=SelectiveArchiveMoveEngine(journal,port)
            val hidden=engine.hide(snapshot,original,token,target)
            assertEquals(SelectiveArchiveMovePhase.HIDDEN_VERIFIED,hidden.phase)
            assertEquals(SelectiveArchiveLocation.HIDDEN,port.pos)
            assertEquals(hidden,engine.hide(snapshot,original,token,target))
            assertEquals(1,port.moveCount)
            val restored=engine.restore(snapshot,original)
            assertEquals(SelectiveArchiveMovePhase.RESTORED_VERIFIED,restored.phase)
            assertEquals(SelectiveArchiveLocation.SOURCE,port.pos)
            assertEquals(restored,engine.restore(snapshot,original))
            assertEquals(2,port.moveCount)
        } finally { dir.deleteRecursively() }
    }

    @Test fun crashAfterHideIsSafelyReconciledWithoutSecondMove()=runBlocking<Unit> {
        val dir=Files.createTempDirectory("archive-replay-hide").toFile()
        try {
            val port=Port(original);port.failAfterMove=true
            val journal=SelectiveArchiveMoveJournal(dir)
            assertThrows(IllegalStateException::class.java) { runBlocking {
                SelectiveArchiveMoveEngine(journal,port).hide(snapshot,original,token,target)
            }}
            assertEquals(SelectiveArchiveLocation.HIDDEN,port.pos)
            assertEquals(SelectiveArchiveMovePhase.HIDE_INTENT,
                journal.read(snapshot.folderId,original.catalogId)?.phase)
            val recovered=SelectiveArchiveMoveEngine(SelectiveArchiveMoveJournal(dir),port)
                .hide(snapshot,original,token,target)
            assertEquals(SelectiveArchiveMovePhase.HIDDEN_VERIFIED,recovered.phase)
            assertEquals(1,port.moveCount)
        } finally { dir.deleteRecursively() }
    }

    @Test fun crashAfterRestoreIsSafelyReconciledWithoutSecondMove()=runBlocking<Unit> {
        val dir=Files.createTempDirectory("archive-replay-restore").toFile()
        try {
            val port=Port(original);val journal=SelectiveArchiveMoveJournal(dir)
            SelectiveArchiveMoveEngine(journal,port).hide(snapshot,original,token,target)
            port.failAfterMove=true
            assertThrows(IllegalStateException::class.java) { runBlocking {
                SelectiveArchiveMoveEngine(journal,port).restore(snapshot,original)
            }}
            assertEquals(SelectiveArchiveMovePhase.RESTORE_INTENT,
                journal.read(snapshot.folderId,original.catalogId)?.phase)
            val restored=SelectiveArchiveMoveEngine(SelectiveArchiveMoveJournal(dir),port)
                .restore(snapshot,original)
            assertEquals(SelectiveArchiveMovePhase.RESTORED_VERIFIED,restored.phase)
            assertEquals(2,port.moveCount)
        } finally { dir.deleteRecursively() }
    }

    @Test fun ambiguousIdForeignMarkerAndChangedReferencesBlockFileOperations()=runBlocking<Unit> {
        for (mode in 0..3) {
            val dir=Files.createTempDirectory("archive-fail-closed-$mode").toFile()
            try {
                val port=Port(original)
                when(mode) {
                    0 -> port.id=999
                    1 -> port.links=false
                    2 -> port.public=null
                    3 -> port.targetIsForeign=true
                }
                val journal=SelectiveArchiveMoveJournal(dir)
                assertThrows(IllegalStateException::class.java) { runBlocking {
                    SelectiveArchiveMoveEngine(journal,port).hide(snapshot,original,token,target)
                }}
                assertEquals(0,port.moveCount)
                assertEquals(SelectiveArchiveLocation.SOURCE,port.pos)
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun occupiedDestinationCannotBeOverwrittenOnRestore()=runBlocking<Unit> {
        val dir=Files.createTempDirectory("archive-occupied").toFile()
        try {
            val port=Port(original);val journal=SelectiveArchiveMoveJournal(dir)
            SelectiveArchiveMoveEngine(journal,port).hide(snapshot,original,token,target)
            port.sourceTaken=true
            assertThrows(IllegalStateException::class.java) { runBlocking {
                SelectiveArchiveMoveEngine(journal,port).restore(snapshot,original)
            }}
            assertEquals(SelectiveArchiveLocation.HIDDEN,port.pos)
            assertEquals(SelectiveArchiveMovePhase.RESTORE_INTENT,
                journal.read(snapshot.folderId,original.catalogId)?.phase)
        } finally { dir.deleteRecursively() }
    }
}
