package com.domenota.medialoader.ui.screens.music

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
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
    @Test fun graphStartsAtLatestAndKeepsManualScrollAcrossMetricAndRefresh() {
        var byTime by mutableStateOf(true)
        var period by mutableStateOf("month")
        var points by mutableStateOf((1..30).map { SmartChartPoint("$it.11",it.toLong(),it*1000L) })
        compose.setContent {MaterialTheme {Box(Modifier.width(300.dp)) {HistoryGraph(points,byTime,period,{})}}}
        fun range()=compose.onNodeWithTag("stats-history-graph").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        compose.waitForIdle()
        val initial=range()
        assertTrue(initial.value()>0);assertEquals(initial.maxValue(),initial.value(),1f)
        compose.onNodeWithTag("stats-history-graph").performTouchInput {swipeRight()}
        compose.waitForIdle()
        val manual=range().value()
        assertTrue(manual<range().maxValue())
        compose.runOnIdle {byTime=false;points=points.map {it.copy(plays=it.plays+1)}}
        compose.waitForIdle()
        assertEquals(manual,range().value(),1f)
        compose.runOnIdle {period="year"}
        compose.waitForIdle()
        val changed=range()
        assertEquals(changed.maxValue(),changed.value(),1f)
    }
    @Test fun zeroYearBucketCanBeSelectedForDrilldown() {
        val date=java.time.LocalDate.of(2026,1,1)
        var selected:SmartChartPoint?=null
        compose.setContent {MaterialTheme {HistoryGraph(listOf(SmartChartPoint("2026",0,0,date)),true,"all",{selected=it})}}
        compose.onNodeWithTag("stats-bucket-2026-01-01").performClick()
        compose.runOnIdle {assertEquals(date,selected?.date)}
    }
}
