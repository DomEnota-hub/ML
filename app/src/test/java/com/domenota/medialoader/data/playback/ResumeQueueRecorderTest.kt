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
        var queue=listOf<MediaItem>();var index=0;var position=0L;var playing=false;var status=Player.STATE_READY
        val player=Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)){_,method,args->
            when(method.name){
                "getMediaItemCount"->queue.size
                "getMediaItemAt"->queue[args!![0] as Int]
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
        val recorder=ResumeQueueRecorder(fake.player,scope,store,PublicListeningIdentity(context){true})
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
}
