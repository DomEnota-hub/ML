package com.domenota.medialoader.data.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class MusicDuplicateShardStoreTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()

    private fun makeSession(groupCount:Int,at:Long):DuplicateSession {
        val epoch=DuplicatePrivacyGuard.generation(context)
        val groups=(0 until groupCount).map { idx ->
            val landmarks=(0 until 32).map{AcousticLandmark(it + idx, it * 3)}
            val one=DuplicateTrack("a$idx","Track $idx",null,"$idx.mp3",
                "content://media/audio/"+(idx*2+1),4096,456,"sha-$idx",null,2000,null,null,
                acoustic=AcousticFingerprint(landmarks),privacyEpoch=epoch)
            val two=one.copy(id="b$idx",filename="copy-$idx.mp3",
                uri="content://media/audio/"+(idx*2+2))
            DuplicateGroup(listOf(one,two),"Track $idx","same",DuplicateKind.ACOUSTIC)
        }
        return DuplicateSession(DuplicateScanResult(groups,4,acousticUnavailable=2,expanded=false,privacyEpoch=epoch),
            emptySet(),emptyMap(),0,0,at)
    }

    @Test fun completedReportUsesMultipleIndependentChunksAndSmallSelectionState()=runBlocking {
        assertTrue(MusicDuplicateSessionStore.clearJournal(context,true))
        val original=makeSession(230,1700000011000L)
        assertTrue(MusicDuplicateSessionStore.save(context,original))
        val loaded=requireNotNull(MusicDuplicateSessionStore.load(context))
        assertEquals(230,loaded.result.groups.size)
        assertEquals(original.result.groups,loaded.result.groups)
        val chosen=loaded.copy(selected=setOf("b4"),kept=mapOf(loaded.result.groups[4].key to "a4"),index=202,offset=14)
        val generation=MusicDuplicateSessionStore.journalGeneration()
        assertTrue(MusicDuplicateSessionStore.saveInteraction(context,chosen,false,generation))
        assertEquals(chosen,MusicDuplicateSessionStore.load(context))
        val folder=File(context.noBackupFilesDir,"music-duplicate-results-v2")
        val dirs=folder.listFiles().orEmpty().filter{it.isDirectory}
        assertEquals(1,dirs.size)
        assertTrue(dirs.single().listFiles().orEmpty().count{it.extension=="jsonl"}>=3)
        val originalTimes=dirs.single().listFiles().orEmpty().filter{it.extension=="jsonl"}.associate{it.name to it.lastModified()}
        assertTrue(MusicDuplicateSessionStore.saveInteraction(context,chosen.copy(index=210),false,generation))
        val newTimes=dirs.single().listFiles().orEmpty().filter{it.extension=="jsonl"}.associate{it.name to it.lastModified()}
        assertEquals(originalTimes,newTimes)
        assertTrue(MusicDuplicateSessionStore.clearJournal(context,true))
    }

    @Test fun unsuccessfulNewSnapshotCannotReplacePreviousCompletedReport()=runBlocking {
        assertTrue(MusicDuplicateSessionStore.clearJournal(context,true))
        val first=makeSession(4,1700000012000L)
        assertTrue(MusicDuplicateSessionStore.save(context,first))
        val wrongEpoch=makeSession(128,1700000013000L).let{
            it.copy(result=it.result.copy(privacyEpoch=it.result.privacyEpoch+1))
        }
        assertFalse(MusicDuplicateSessionStore.save(context,wrongEpoch))
        assertEquals(first.scannedAt,MusicDuplicateSessionStore.load(context)?.scannedAt)
        assertTrue(MusicDuplicateSessionStore.clearJournal(context,true))
    }

    @Test fun corruptShardFailsClosedInsteadOfPromotingIncompleteResult()=runBlocking {
        assertTrue(MusicDuplicateSessionStore.clearJournal(context,true))
        assertTrue(MusicDuplicateSessionStore.save(context,makeSession(120,1700000014000L)))
        val root=File(context.noBackupFilesDir,"music-duplicate-results-v2")
        val shard=root.listFiles().orEmpty().first{it.isDirectory}.listFiles().orEmpty().first{it.extension=="jsonl"}
        shard.appendText("\ninvalid")
        assertNull(MusicDuplicateSessionStore.load(context))
        assertTrue(MusicDuplicateSessionStore.clearJournal(context,true))
    }

    @Test fun lightweightSelectionForOldPartialSurvivesReopen()=runBlocking {
        assertTrue(MusicDuplicateSessionStore.clearJournal(context,true))
        val initial=makeSession(2,1700000015000L)
        assertTrue(MusicDuplicateSessionStore.savePartial(context,initial))
        val saved=initial.copy(selected=setOf("b0"),index=1,offset=9)
        assertTrue(MusicDuplicateSessionStore.saveInteraction(context,saved,true))
        assertEquals(saved,MusicDuplicateSessionStore.partial(context))
        assertNull(MusicDuplicateSessionStore.load(context))
        assertTrue(MusicDuplicateSessionStore.clearJournal(context,true))
    }
}
