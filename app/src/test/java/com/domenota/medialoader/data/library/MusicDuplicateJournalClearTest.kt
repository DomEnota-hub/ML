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
}
