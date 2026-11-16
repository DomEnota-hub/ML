package com.domenota.medialoader.data.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ResumeQueueRecorderTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val name="resume-recorder-test.json"
    @Before fun setup(){File(context.noBackupFilesDir,name).delete()}
    @After fun cleanup(){File(context.noBackupFilesDir,name).delete()}
    private fun item(id:String,playlist:String,private:Boolean=false)=AudioPlaybackMetadata.item(
        DownloadEntity(id,"device","$id.mp3",MediaType.AUDIO,DownloadState.COMPLETED,
            "content://media/external/audio/media/${id.hashCode().toUInt()}",123L,100L,isPrivate=private),
        setOf("file-stat:$id"),playlist,"playlist:$playlist")
    private class Fake {
        var reads=0;var queue=listOf<MediaItem>();var index=0;var position=0L;var playing=false;var status=Player.STATE_READY
        val player=Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)){_,method,args->
            when(method.name){
                "getMediaItemCount"->queue.size
                "getMediaItemAt"->{reads++;queue[args!![0] as Int]}
                "getCurrentMediaItem"->queue.getOrNull(index)
                "getCurrentMediaItemIndex"->index
                "getCurrentPosition"->position
                "getDuration"->180_000L
                "isPlaying"->playing
                "getPlaybackState"->status
                "getShuffleModeEnabled"->false
                "getRepeatMode"->Player.REPEAT_MODE_OFF
                "getPlaybackParameters"->PlaybackParameters.DEFAULT
                "addListener","removeListener"->null
                else->throw UnsupportedOperationException(method.name)
            }
        } as Player
    }
    @Test fun serviceRecorderKeepsDifferentQueuesAndNeverRecordsPrivatePlayback()=runBlocking {
        val store=ResumeQueueStore.forTest(context,name)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val fake=Fake()
        val recorder=ResumeQueueRecorder(fake.player,scope,store,PublicListeningIdentity(context){true},Dispatchers.Unconfined)
        try {
            recorder.attach()
            fake.queue=listOf(item("a","one"),item("b","one"));fake.index=1;fake.position=102_000;fake.playing=true
            recorder.refresh()
            val first=withTimeout(5000){store.sessions.first{it.any{s->s.id=="playlist:one"}}}.single()
            assertEquals(1,first.index);assertEquals(102_000L,first.positionMs)
            fake.playing=false;fake.position=103_000;recorder.refresh()
            withTimeout(5000){store.sessions.first{it.firstOrNull()?.positionMs==103_000L}}
            fake.queue=listOf(item("c","two"));fake.index=0;fake.position=30_000;fake.playing=true
            recorder.refresh()
            val both=withTimeout(5000){store.sessions.first{it.size==2}}
            assertEquals(listOf("playlist:two","playlist:one"),both.map{it.id})
            fake.queue=listOf(item("secret","private",true));fake.position=45_000
            recorder.refresh()
            assertFalse(store.sessions.value.any{it.id=="playlist:private"})
            fake.queue=listOf(item("c","two"));fake.status=Player.STATE_ENDED;fake.playing=false
            recorder.refresh()
            val remaining=withTimeout(5000){store.sessions.first{it.size==1}}
            assertEquals("playlist:one",remaining.single().id)
        } finally{recorder.release();scope.cancel();store.closeForTest()}
    }
    @Test fun editingSavedQueueWhilePlayingDoesNotTouchPlayerOrRecreateRemovedOccurrence()=runBlocking {
        val store=ResumeQueueStore.forTest(context,name)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val fake=Fake().apply {queue=listOf(item("a","one"),item("a","one"),item("b","one"));index=1;position=50_000;playing=true}
        val recorder=ResumeQueueRecorder(fake.player,scope,store,PublicListeningIdentity(context){true},Dispatchers.Unconfined)
        try {
            recorder.attach()
            val saved=withTimeout(5000){store.sessions.first {it.isNotEmpty()}}.single()
            store.edit(saved,setOf(saved.occurrenceIds[0]),ResolvedResumeQueue(saved,listOf("a","a","b")))
            val edited=store.readForTest().single()
            fake.position=60_000;recorder.refresh(false)
            recorder.release()
            // A barrier through the same channel waits until the old checkpoint and finish commands are consumed.
            fake.status=Player.STATE_ENDED;recorder.refresh(false)
            store.submit(edited.copy(id="barrier"),store.lease("barrier"))
            withTimeout(5000){store.sessions.first {records->records.any {it.id=="barrier"}}}
            assertEquals(edited,store.readForTest().first {it.id==saved.id})
            assertEquals(3,fake.queue.size);assertEquals(1,fake.index)
            assertEquals(60_000L,fake.position);assertTrue(fake.playing)
        } finally {scope.cancel();store.closeForTest()}
    }

    @Test fun largeQueueScalarUpdatesDoNotEnumerateMembershipAgain()=runBlocking {
        val store=ResumeQueueStore.forTest(context,name)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val fake=Fake().apply {queue=(0 until 2500).map{item("large$it","large")};index=1200;position=30_000;playing=true}
        val recorder=ResumeQueueRecorder(fake.player,scope,store,PublicListeningIdentity(context){true},Dispatchers.Unconfined)
        try {
            recorder.attach()
            assertEquals(2500,fake.reads)
            repeat(20){fake.position+=100;recorder.refresh(false)}
            assertEquals(2500,fake.reads)
            fake.index=1201;fake.position=200;recorder.refresh(false)
            assertEquals(2500,fake.reads)
            fake.queue=fake.queue.reversed();recorder.refresh(true)
            assertEquals(5000,fake.reads)
        } finally {recorder.release();scope.cancel();store.closeForTest()}
    }

    private class DelayedPreparation:CoroutineDispatcher(){
        private val pending=java.util.ArrayDeque<Runnable>()
        override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable){pending.addLast(block)}
        fun drain(){while(pending.isNotEmpty())pending.removeFirst().run()}
    }
    @Test fun supersededWorkerCannotSaveOldQueueOrPrivateAccess()=runBlocking {
        val store=ResumeQueueStore.forTest(context,name)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val worker=DelayedPreparation()
        val fake=Fake().apply{queue=listOf(item("a","old"));position=5000;playing=true}
        var access=PublicListeningIdentity.Access(true,emptySet())
        val recorder=ResumeQueueRecorder(fake.player,scope,store,PublicListeningIdentity(context,{access}){true},worker)
        try{
            recorder.attach();assertTrue(store.sessions.value.isEmpty())
            fake.queue=listOf(item("b","new"));recorder.refresh()
            worker.drain()
            val saved=withTimeout(5000){store.sessions.first{it.isNotEmpty()}}
            assertEquals(listOf("playlist:new"),saved.map{it.id})
            fake.queue=listOf(item("secret","blocked"));recorder.refresh()
            access=PublicListeningIdentity.Access(true,setOf("secret"));worker.drain()
            val barrier=saved.single().copy(id="barrier");store.submit(barrier)
            withTimeout(5000){store.sessions.first{it.any{s->s.id=="barrier"}}}
            assertFalse(store.sessions.value.any{it.id=="playlist:blocked"||it.id=="playlist:old"})
        }finally{recorder.release();worker.drain();scope.cancel();store.closeForTest()}
    }
    @Test fun clearDuringPreparationCannotRecreateEvenPreviouslyUnknownQueue()=runBlocking {
        val store=ResumeQueueStore.forTest(context,name)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val worker=DelayedPreparation()
        val fake=Fake().apply{queue=listOf(item("a","new"));position=5000;playing=true}
        val recorder=ResumeQueueRecorder(fake.player,scope,store,PublicListeningIdentity(context){true},worker)
        try{
            recorder.attach();store.clearSaved();worker.drain()
            val barrier=ResumeQueueSession("barrier",null,listOf(setOf("app:a")),0,1,100,false,0,1f,100)
            store.submit(barrier);withTimeout(5000){store.sessions.first{it.any{s->s.id=="barrier"}}}
            assertEquals(listOf("barrier"),store.readForTest().map{it.id})
        }finally{recorder.release();worker.drain();scope.cancel();store.closeForTest()}
    }
    @Test fun releaseFinishesPendingCheckpointWithFrozenPosition()=runBlocking {
        val store=ResumeQueueStore.forTest(context,name)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val worker=DelayedPreparation()
        val fake=Fake().apply{queue=listOf(item("a","one"),item("b","one"));index=1;position=5000;playing=true}
        val recorder=ResumeQueueRecorder(fake.player,scope,store,PublicListeningIdentity(context){true},worker)
        try{
            recorder.attach();fake.position=9000;recorder.release();fake.queue=emptyList();fake.position=0;worker.drain()
            val saved=withTimeout(5000){store.sessions.first{it.isNotEmpty()}}.single()
            assertEquals(1,saved.index);assertEquals(9000L,saved.positionMs);assertEquals(2,saved.entries.size)
        }finally{scope.cancel();store.closeForTest()}
    }
    @Test fun frozenHiddenIdentityGatePreservesCanonicalIdsAndUriAliases() {
        val prefs=context.getSharedPreferences("music_folder_visibility",android.content.Context.MODE_PRIVATE)
        val old=prefs.getStringSet("hidden_tracks",emptySet()).orEmpty().toSet()
        fun encode(value:String)=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        fun hide(id:String,uri:String){assertTrue(prefs.edit().putStringSet("hidden_tracks",setOf("${encode(id)}.${encode(uri)}")).commit())}
        val track=item("a","one")
        val uri=track.mediaMetadata.extras!!.getString(AudioPlaybackMetadata.PUBLIC_URI)!!
        val identity=PublicListeningIdentity(context,{PublicListeningIdentity.Access(true,emptySet())}){true}
        try {
            assertTrue(prefs.edit().remove("hidden_tracks").commit())
            val before=identity.snapshot();assertTrue(before.keys(track).isNotEmpty())
            hide("a","unrelated");assertTrue(identity.keys(track).isEmpty())
            assertTrue(before.keys(track).isNotEmpty());assertNotEquals(before.token,identity.visibilityToken())
            hide("unrelated",uri);assertTrue(identity.keys(track).isEmpty())
            val alias=AudioPlaybackMetadata.item(DownloadEntity("alias","device","alias.mp3",MediaType.AUDIO,
                DownloadState.COMPLETED,"content://media/external/audio/media/999",123L,100L),setOf("uri:$uri"),"one","playlist:one")
            assertTrue(identity.keys(alias).isEmpty())
            assertTrue(prefs.edit().putStringSet("hidden_tracks",setOf("${encode("a") }==.${encode("unrelated")}")).commit())
            assertTrue(identity.keys(track).isNotEmpty())
        } finally { prefs.edit().putStringSet("hidden_tracks",old).commit() }
    }
}
