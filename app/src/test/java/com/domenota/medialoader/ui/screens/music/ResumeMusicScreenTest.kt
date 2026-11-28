package com.domenota.medialoader.ui.screens.music

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.playback.ResolvedResumeQueue
import com.domenota.medialoader.data.playback.ResumeQueueSession
import com.domenota.medialoader.data.playback.ResumeQueueStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w393dp-h852dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ResumeMusicScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun savedQueueFilterPreservesOccurrencesAndRecheckIsExplicit(){
        val saved=ResumeQueueSession("test",null,listOf(setOf("a"),setOf("b"),setOf("a")),1,30000,180000,false,0,1f,100)
        val resolved=ResolvedResumeQueue(saved,listOf("a",null,"a"))
        val row=DownloadEntity("a","test","Song.mp3",MediaType.AUDIO,DownloadState.COMPLETED,null,100L,1L)
        val store=ResumeQueueStore.forTest(compose.activity,"resume-ui-test.json")
        var rechecks=0;var resumes=0
        try {
            compose.setContent{MaterialTheme{
                ResumeMusicScreen(listOf(resolved),mapOf("a" to row),emptyMap(),false,false,{}, {resumes++},store,{rechecks++})
            }}
            compose.onNodeWithText("Состав очереди").performClick()
            compose.onNodeWithText("Недоступные · 1").performClick()
            compose.onNodeWithTag("resume-entry-"+saved.occurrenceIds[0]).assertDoesNotExist()
            compose.onNodeWithTag("resume-entry-"+saved.occurrenceIds[1]).assertExists()
            compose.onNodeWithText("Выбрать показанные").performClick()
            compose.onNodeWithText("Выбрано: 1").assertExists()
            compose.onNodeWithText("Все · 3").performClick()
            compose.onNodeWithText("Выбрано: 1").assertExists()
            compose.onNodeWithContentDescription("Действия с очередью").performClick()
            compose.onNodeWithText("Перепроверить доступность").performClick()
            compose.runOnIdle{assertEquals(1,rechecks);assertEquals(0,resumes)}
        }finally{store.closeForTest()}
    }
}
