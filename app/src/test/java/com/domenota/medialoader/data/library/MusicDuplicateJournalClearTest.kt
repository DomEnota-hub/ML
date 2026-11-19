package com.domenota.medialoader.data.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class MusicDuplicateJournalClearTest {
    @Test fun clearReportsKeepsPartialTrashVisualAndRejectsStaleSave()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val epoch=DuplicatePrivacyGuard.generation(context)
        fun session(at:Long)=DuplicateSession(DuplicateScanResult(emptyList(),0,privacyEpoch=epoch),emptySet(),emptyMap(),0,0,at)
        assertTrue(MusicDuplicateSessionStore.clearJournal(context,true))
        assertTrue(MusicDuplicateSessionStore.save(context,session(100)))
        assertTrue(MusicDuplicateSessionStore.save(context,session(200)))
        MusicDuplicateSessionStore.savePartial(context,session(300))
        val trash=DuplicateTrashReport(400,epoch,emptyList())
        assertTrue(MusicDuplicateSessionStore.saveTrashReport(context,trash))
        DuplicateReportJournal.archive(context,"visual",500,"visual")
        val stale=MusicDuplicateSessionStore.journalGeneration()
        assertTrue(MusicDuplicateSessionStore.clearJournal(context,false))
        assertTrue(MusicDuplicateSessionStore.history(context).isEmpty())
        assertEquals(300L,MusicDuplicateSessionStore.partial(context)?.scannedAt)
        assertEquals(trash,MusicDuplicateSessionStore.trashReport(context))
        assertEquals(listOf("visual"),DuplicateReportJournal.history(context,"visual"))
        assertFalse(MusicDuplicateSessionStore.save(context,session(200),stale))
        assertTrue(MusicDuplicateSessionStore.clearJournal(context,true))
        assertNull(MusicDuplicateSessionStore.partial(context))
        assertTrue(MusicDuplicateSessionStore.history(context).isEmpty())
        assertEquals(trash,MusicDuplicateSessionStore.trashReport(context))
        DuplicateReportJournal.clear(context,"visual")
    }

    @Test fun provisionalGroupsRetainSelectionsWithoutBecomingCompletedReport()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        assertTrue(MusicDuplicateSessionStore.clearJournal(context,true))
        val epoch=DuplicatePrivacyGuard.generation(context)
        val first=DuplicateTrack("a","Песня",null,"a.mp3","content://media/audio/1",123,456,"sha-a",null,1000,null,null,privacyEpoch=epoch)
        val second=first.copy(id="b",filename="b.mp3",uri="content://media/audio/2")
        val group=DuplicateGroup(listOf(first,second),"Песня","Точное совпадение",DuplicateKind.EXACT)
        val session=DuplicateSession(
            DuplicateScanResult(listOf(group),0,expanded=false,privacyEpoch=epoch),
            setOf("b"),mapOf(group.key to "a"),0,0,1700000000000L
        )
        val generation=MusicDuplicateSessionStore.journalGeneration()
        assertTrue(MusicDuplicateSessionStore.savePartial(context,session,generation))
        assertEquals(session,MusicDuplicateSessionStore.partial(context))
        assertNull("Незавершённый поиск не должен превращаться в завершённый",MusicDuplicateSessionStore.load(context))
        assertTrue(MusicDuplicateSessionStore.history(context).isEmpty())
        assertTrue(MusicDuplicateSessionStore.clearJournal(context,true))
        assertFalse(MusicDuplicateSessionStore.savePartial(context,session,generation))
        assertNull(MusicDuplicateSessionStore.partial(context))
    }
}
