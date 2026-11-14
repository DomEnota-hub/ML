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
    @Test fun editedAndDeletedSnapshotsCannotBeResurrectedByOldRecorderLease()=runBlocking {
        val saved=ResumeQueueSession("q",null,listOf(setOf("a"),setOf("a"),setOf("b")),1,50_000,180_000,true,2,1.5f,100)
        val store=ResumeQueueStore.forTest(context,name)
        try {
            store.save(saved)
            val lease=store.lease(saved.id)
            store.edit(saved,setOf(saved.occurrenceIds[0]),ResolvedResumeQueue(saved,listOf("a","a","b")))
            val edited=store.readForTest().single()
            store.save(saved.copy(positionMs=90_000),lease)
            store.remove(saved.id,lease)
            assertEquals(edited,store.readForTest().single())
            assertEquals(50_000L,edited.positionMs)
            store.deleteSaved(saved.id)
            store.save(saved,lease)
            assertTrue(store.readForTest().isEmpty())
            store.save(edited,store.lease(saved.id))
            store.clearSaved()
            store.save(edited,store.lease(saved.id)-1)
            assertTrue(store.readForTest().isEmpty())
        } finally {store.closeForTest()}
    }
    @Test fun occurrenceIdsAndModesSurviveEditAndRestart()=runBlocking {
        val saved=ResumeQueueSession("q","p",listOf(setOf("a"),setOf("a"),setOf("b")),1,50_000,180_000,true,2,1.5f,100)
        val store=ResumeQueueStore.forTest(context,name)
        store.save(saved)
        store.edit(saved,setOf(saved.occurrenceIds[0]),ResolvedResumeQueue(saved,listOf("a","a","b")))
        val expected=store.readForTest().single();store.closeForTest()
        val restored=ResumeQueueStore.forTest(context,name)
        try {assertEquals(expected,restored.readForTest().single())} finally {restored.closeForTest()}
    }
    @Test fun originalV1QueueWithoutOccurrenceIdsRemainsReadable()=runBlocking {
        val saved=ResumeQueueSession("legacy",null,listOf(setOf("a"),setOf("a")),1,50_000,180_000,false,0,1f,100)
        val store=ResumeQueueStore.forTest(context,name)
        store.save(saved);store.closeForTest()
        val file=File(context.noBackupFilesDir,name)
        val json=org.json.JSONObject(file.readText())
        json.getJSONArray("sessions").getJSONObject(0).remove("occurrences")
        file.writeText(json.toString())
        val restored=ResumeQueueStore.forTest(context,name)
        try {assertEquals(saved,restored.readForTest().single())} finally {restored.closeForTest()}
    }
}
