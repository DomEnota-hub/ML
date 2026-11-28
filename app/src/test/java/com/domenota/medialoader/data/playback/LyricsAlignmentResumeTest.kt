package com.domenota.medialoader.data.playback

import android.net.Uri
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.DuplicatePrivacyGuard
import com.domenota.medialoader.data.library.LibraryMediaRef
import com.domenota.medialoader.data.playback.alignment.AlignmentServiceRunSlot
import com.domenota.medialoader.data.playback.alignment.LyricsAlignmentCoordinator
import com.domenota.medialoader.data.tasks.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LyricsAlignmentResumeTest {
    @Test fun resumeKeepsTaskHistoryButOldServiceCannotPauseOrStopTheNewExecution() {
        val context=RuntimeEnvironment.getApplication()
        val shadow=shadowOf(context)
        val audio=File.createTempFile("lyrics-resume-",".mp3",context.cacheDir).apply{writeBytes(byteArrayOf(1,2,3))}
        val uri=Uri.fromFile(audio).toString()
        val row=DownloadEntity(audio.name,"device","Track",MediaType.AUDIO,DownloadState.COMPLETED,uri,null,0)
        val access=AudioBookmarkAdmission(LibraryMediaRef(row.id,PublicMediaIdentity.uriKeys(uri),row,row.id),DuplicatePrivacyGuard.generation(context))
        var ownTaskId:String?=null
        try {
            LyricsAlignmentCoordinator.start(context,access,"a".repeat(64),TrackLyrics("Original",emptyList(),LyricsOrigin.IMPORTED))
            val taskId=requireNotNull(LyricsAlignmentCoordinator.notificationTaskId)
            ownTaskId=taskId
            val first=requireNotNull(requireNotNull(shadow.nextStartedService).getStringExtra("token"))
            LyricsAlignmentCoordinator.serviceStopped(first)
            val paused=TaskCenter.registry.liveTasks.value.single{it.id==taskId}
            assertEquals(TaskStatus.PAUSED,paused.status)
            assertTrue(TaskCenter.registry.dispatch(paused.id,TaskCommand.RESUME))
            val second=requireNotNull(requireNotNull(shadow.nextStartedService).getStringExtra("token"))
            assertNotEquals(first,second)
            val resumed=TaskCenter.registry.liveTasks.value.single{it.id==paused.id}
            assertEquals(paused.details?.correlation,resumed.details?.correlation)
            assertEquals(TaskStatus.QUEUED,resumed.status)
            assertEquals(setOf(TaskCommand.CANCEL),resumed.commands)
            val service=AlignmentServiceRunSlot()
            assertTrue(service.claim(first,1,first));assertTrue(service.claim(second,2,second))
            assertNull(service.finish(first))
            LyricsAlignmentCoordinator.serviceStopped(first)
            assertTrue(LyricsAlignmentCoordinator.state.value.running)
            assertEquals(second,LyricsAlignmentCoordinator.token())
        } finally {
            ownTaskId?.let { id->
                LyricsAlignmentCoordinator.token()?.let(LyricsAlignmentCoordinator::serviceStopped)
                TaskCenter.registry.dispatch(id,TaskCommand.CANCEL)
                TaskCenter.registry.remove(TaskOwner.LYRICS,id)
            }
            audio.delete()
        }
    }
}
