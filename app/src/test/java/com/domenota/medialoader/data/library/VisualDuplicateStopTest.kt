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
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class VisualDuplicateStopTest {
    @Test fun stopIsResumableAndPreservesCompletedPartialAndJournal()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val epoch=DuplicatePrivacyGuard.generation(context)
        VisualDuplicateSessionStore.clearForPrivacy(context)
        val first=VisualDuplicateSession(VisualScanResult(emptyList(),0,epoch),emptySet(),0,0,100)
        val latest=first.copy(scannedAt=200)
        val partial=first.copy(scannedAt=300,result=first.result.copy(coverage=VisualScanCoverage(LibrarySource.APP,10,computed=3)))
        assertTrue(VisualDuplicateSessionStore.save(context,first))
        assertTrue(VisualDuplicateSessionStore.save(context,latest))
        VisualDuplicateSessionStore.savePartial(context,partial)
        VisualDuplicateBackgroundScan.start(context,emptyList(),LibrarySource.APP,epoch)
        val token=VisualDuplicateBackgroundScan.state.value.token
        VisualDuplicateBackgroundScan.partial(token,partial.result)
        VisualDuplicateBackgroundScan.finish(context,token,null,"Cancellation must not become an error",cancelled=true)
        val status=VisualDuplicateBackgroundScan.state.value
        assertFalse(status.running)
        assertNull(status.error)
        assertTrue(status.stopped)
        assertEquals(partial.result,status.partial)
        assertTrue(VisualDuplicateBackgroundScan.interrupted(context))
        assertTrue(VisualDuplicateBackgroundScan.stopped(context))
        assertEquals("Остановлен",VisualDuplicateBackgroundScan.journalStatus(context))
        assertEquals(latest,VisualDuplicateSessionStore.load(context))
        assertEquals(partial,VisualDuplicateSessionStore.partial(context))
        assertEquals(listOf(200L,100L),VisualDuplicateSessionStore.history(context).map{it.scannedAt})
        // Reset in-memory state: the persisted stop marker and reports survive cold reentry.
        VisualDuplicateBackgroundScan.invalidateForPrivacy()
        assertTrue(VisualDuplicateBackgroundScan.stopped(context))
        assertEquals(partial,VisualDuplicateSessionStore.partial(context))
        VisualDuplicateBackgroundScan.start(context,emptyList(),LibrarySource.APP,epoch)
        assertTrue(VisualDuplicateBackgroundScan.state.value.running)
        assertFalse(VisualDuplicateBackgroundScan.stopped(context))
        val resumed=VisualDuplicateBackgroundScan.state.value.token
        VisualDuplicateBackgroundScan.finish(context,resumed,latest)
        assertFalse(VisualDuplicateBackgroundScan.interrupted(context))
        assertFalse(VisualDuplicateBackgroundScan.stopped(context))
    }

    @Test fun explicitPauseIsNeutralResumableAndKeepsItsJournalLabel(){
        val context=ApplicationProvider.getApplicationContext<Context>()
        VisualDuplicateBackgroundScan.start(context,emptyList(),LibrarySource.APP,DuplicatePrivacyGuard.generation(context))
        VisualDuplicateBackgroundScan.finish(context,VisualDuplicateBackgroundScan.state.value.token,null,"Pause is not an error",paused=true)
        assertNull(VisualDuplicateBackgroundScan.state.value.error)
        assertTrue(VisualDuplicateBackgroundScan.interrupted(context))
        assertTrue(VisualDuplicateBackgroundScan.stopped(context))
        assertEquals("Приостановлен",VisualDuplicateBackgroundScan.journalStatus(context))
    }

    @Test fun realFailureRemainsAnErrorInsteadOfStoppedState(){
        val context=ApplicationProvider.getApplicationContext<Context>()
        VisualDuplicateBackgroundScan.start(context,emptyList(),LibrarySource.APP,DuplicatePrivacyGuard.generation(context))
        VisualDuplicateBackgroundScan.finish(context,VisualDuplicateBackgroundScan.state.value.token,null,"Actual failure")
        assertEquals("Actual failure",VisualDuplicateBackgroundScan.state.value.error)
        assertFalse(VisualDuplicateBackgroundScan.stopped(context))
        assertEquals("Прерван",VisualDuplicateBackgroundScan.journalStatus(context))
    }
    @Test fun stoppingBeforeFirstHundredPublishesFinalBatchAndResumeReusesFingerprints()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        VisualDuplicateScanner.clearForPrivacy(context)
        val directory=java.io.File(context.cacheDir,"synthetic-visual-stop").apply{mkdirs()}
        val bitmap=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        val refs=(1..2).map{index->
            val file=java.io.File(directory,"$index.png")
            file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            LibraryMediaRef("stop-$index",setOf("stop-$index"),
                com.domenota.medialoader.core.database.DownloadEntity("stop-$index","device",file.name,
                    com.domenota.medialoader.core.model.MediaType.PHOTO,com.domenota.medialoader.core.model.DownloadState.COMPLETED,
                    android.net.Uri.fromFile(file).toString(),file.length(),file.lastModified()),null)
        }
        bitmap.recycle()
        var partial:VisualScanResult?=null
        try {
            VisualDuplicateScanner.scan(context,refs,LibrarySource.APP,onPartial={partial=it}) {
                if(it.checked==2)throw kotlinx.coroutines.CancellationException("Synthetic Stop")
            }
            fail("Scan should stop")
        }catch(_:kotlinx.coroutines.CancellationException){}
        assertEquals(2,partial?.coverage?.checked)
        assertEquals(2,partial?.groups?.single()?.files?.size)
        val resumed=VisualDuplicateScanner.scan(context,refs,LibrarySource.APP)
        assertEquals(2,resumed.coverage?.cached)
        assertEquals(0,resumed.coverage?.computed)
        assertEquals(2,resumed.groups.single().files.size)
        directory.deleteRecursively()
        VisualDuplicateScanner.clearForPrivacy(context)
    }

}
