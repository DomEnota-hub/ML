package com.domenota.medialoader.data.playback

import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ResumeQueueStoreTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val name="resume-test.json"
    @Before fun setup(){File(context.noBackupFilesDir,name).delete()}
    @After fun cleanup(){File(context.noBackupFilesDir,name).delete()}
    @Test fun multipleSessionsPersistAcrossStoreRecreation()=runBlocking {
        val session=ResumeQueueSession("one","p",listOf(setOf("app:a"),setOf("app:b")),1,102_000,180_000,true,2,1.5f,100)
        val store=ResumeQueueStore.forTest(context,name)
        store.save(session);store.save(session.copy(id="two",positionMs=20_000));store.closeForTest()
        val restored=ResumeQueueStore.forTest(context,name)
        try {
            assertEquals(listOf("two","one"),restored.readForTest().map{it.id})
            assertEquals(session,restored.readForTest().last())
            restored.remove("two");assertEquals(listOf("one"),restored.readForTest().map{it.id})
        } finally{restored.closeForTest()}
    }
}
