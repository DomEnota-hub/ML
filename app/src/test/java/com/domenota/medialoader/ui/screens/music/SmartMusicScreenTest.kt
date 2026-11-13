package com.domenota.medialoader.ui.screens.music

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.playback.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmartMusicScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun track(id:String)=DownloadEntity(id,"device","$id.mp3",MediaType.AUDIO,DownloadState.COMPLETED,
        "content://media/external/audio/media/1",100L,1L,sourceAudioTitle="Трек $id")
    @Test fun forgottenPeriodAndEmptyStateAreUsable(){
        var days by mutableStateOf(30L)
        compose.setContent{MaterialTheme{
            SmartMusicScreen(SmartMusicPage.FORGOTTEN,SmartMusicSnapshot(),emptyMap(),SmartStatsPeriod.MONTH,{},
                50,{},days,{days=it},false,null,null,false,emptySet(),{},{},{_,_->},{_,_->},{_,_->},{_,_->},{},{},{},null,null)
        }}
        compose.onNodeWithText("60 дней").performClick()
        compose.runOnIdle{assertEquals(60L,days)}
        compose.onNodeWithText("Слушать").assertIsNotEnabled()
    }
    @Test fun resumeWithMissingFilesRequiresExplicitConsent(){
        val session=ResumeQueueSession("q","p",listOf(setOf("missing"),setOf("app:b")),1,102_000,180_000,false,0,1f,100)
        val resolved=ResolvedResumeQueue(session,listOf(null,"b"))
        var restored:ResolvedResumeQueue?=null
        compose.setContent{MaterialTheme{ResumeMusicScreen(listOf(resolved),mapOf("b" to track("b")),mapOf("p" to "Мой плейлист"),false,false,{},{restored=it})}}
        compose.onNodeWithText("Мой плейлист").assertIsDisplayed()
        compose.onNodeWithText("Продолжить").performClick()
        compose.onNodeWithText("Часть очереди недоступна").assertIsDisplayed()
        compose.runOnIdle{assertNull(restored)}
        compose.onNodeWithText("Продолжить с доступными").performClick()
        compose.runOnIdle{assertEquals(resolved,restored)}
    }
    @Test fun missingCurrentTrackCannotBeResumed(){
        val session=ResumeQueueSession("q",null,listOf(setOf("app:a")),0,10_000,180_000,false,0,1f,100)
        compose.setContent{MaterialTheme{ResumeMusicScreen(listOf(ResolvedResumeQueue(session,listOf(null))),emptyMap(),emptyMap(),false,false,{},{error("Must not resume")})}}
        compose.onNodeWithText("Продолжить").assertIsNotEnabled()
    }
}
