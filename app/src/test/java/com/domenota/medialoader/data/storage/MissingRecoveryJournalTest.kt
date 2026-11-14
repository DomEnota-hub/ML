package com.domenota.medialoader.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.download.MissingRecoveryFailure
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MissingRecoveryJournalTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val journal get()=MissingRecoveryJournal(context)
    private val row=DownloadEntity("old","instagram","old.png",MediaType.PHOTO,DownloadState.COMPLETED,
        "content://media/external/images/media/1",10,123456)
    private val files=mutableListOf<File>()
    @Before fun start(){journal.clear()}
    @After fun finish(){journal.clear();files.forEach{it.delete()}}
    private fun temp()=File.createTempFile("recovery-test-",".png",context.cacheDir).also{files+=it}
    private fun image():File{
        val file=temp()
        val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        file.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        bitmap.recycle()
        return file
    }
    private suspend fun stage():Pair<File,String>{
        val source=image()
        val target=temp()
        val uri=Uri.fromFile(target)
        val publication=journal.publication(row)
        publication.prepare(source,"image/png")
        publication.created(uri)
        source.copyTo(target,overwrite=true)
        return target to uri.toString()
    }
    @Test fun journalSurvivesReopenAndVerifiesBytesBeforeCommit()=runBlocking{
        val (target,uri)=stage()
        val reopened=MissingRecoveryJournal(context)
        assertEquals(row.id,reopened.read()?.historyId)
        assertEquals(target.length(),reopened.verify(uri))
        reopened.reconcile({0},{raw->assertEquals(uri,raw);target.delete()})
        assertFalse(target.exists())
        assertNull(journal.read())
    }
    @Test fun committedReferenceIsNeverDeletedEvenAfterRestart()=runBlocking{
        val (target,uri)=stage()
        journal.reconcile({raw->assertEquals(uri,raw);1},{error("Referenced file must never be deleted")})
        assertTrue(target.exists())
        assertNull(journal.read())
    }
    @Test fun changedUnreferencedFileIsPreservedAndAnotherWriteBlocked()=runBlocking{
        val (target,uri)=stage()
        target.writeText("different data")
        assertTrue(runCatching{journal.reconcile({0},{error("Unverified file must not be deleted")})}
            .exceptionOrNull() is MissingRecoveryFailure)
        assertEquals(uri,journal.read()?.uri)
        assertEquals("different data",target.readText())
    }
    @Test fun invalidStageDoesNotPublishAndInterruptedReservationCanBeCleared()=runBlocking{
        val invalid=temp().apply{writeText("not an image")}
        assertTrue(runCatching{journal.publication(row).prepare(invalid,"image/png")}.isFailure)
        assertNull(journal.read())
        journal.publication(row).prepare(image(),"image/png")
        assertNull(journal.read()?.uri)
        journal.reconcile({error("No allocated URI")},{error("No allocated URI")})
        assertNull(journal.read())
    }
}
