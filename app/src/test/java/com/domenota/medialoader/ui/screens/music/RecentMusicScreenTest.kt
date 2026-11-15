package com.domenota.medialoader.ui.screens.music

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import java.time.*
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
class RecentMusicScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun track(id:String,day:Int)=DownloadEntity(id,"device","$id.mp3",MediaType.AUDIO,DownloadState.COMPLETED,
        "content://media/external/audio/media/$id",100L,LocalDate.of(2026,11,day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
    @Test fun initialLoadingIsNotPresentedAsAnEmptyLibrary(){
        val loading=androidx.compose.runtime.mutableStateOf(true)
        compose.setContent{MaterialTheme{RecentMusicScreen(emptyList(),loading.value,null,false,{}, {_,_->},{_,_->},{_,_->})}}
        compose.onNodeWithText("Загрузка музыки…").assertExists()
        compose.onNodeWithText("0 треков").assertDoesNotExist()
        compose.runOnIdle{loading.value=false}
        compose.onNodeWithText("0 треков").assertExists()
    }
    @Test fun calendarDisablesEmptyDaysAndPlaysWholeChosenDay(){
        val rows=listOf(track("a",15),track("b",15),track("c",3),track("d",3))
        var queue:List<String>?=null
        compose.setContent{MaterialTheme{RecentMusicScreen(rows,false,null,false,{}, {ids,_->queue=ids},{_,_->},{_,_->})}}
        compose.onNodeWithTag("recent-day-2026-11-15").assertExists()
        compose.onNodeWithTag("recent-day-2026-11-14").assertDoesNotExist()
        compose.onNodeWithTag("recent-calendar-mode").performClick()
        compose.onNodeWithTag("recent-date-2026-11-14").assertIsNotEnabled()
        compose.onNodeWithTag("recent-date-2026-11-03").assertIsEnabled().performClick()
        compose.onNodeWithTag("recent-calendar-list").performScrollToNode(hasTestTag("recent-open-day"))
        compose.onNodeWithTag("recent-open-day").assertIsDisplayed().performClick()
        compose.onNodeWithText("Слушать").performClick()
        compose.runOnIdle{assertEquals(listOf("c","d"),queue)}
    }
}
