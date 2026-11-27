package com.domenota.medialoader.ui.screens.tasks
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.domenota.medialoader.data.tasks.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class TaskCenterUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun darkOverviewActiveCounterOpensWholeSweep(){
        val task=TaskSnapshot(taskId(TaskOwner.WAVEFORM,"test"),TaskKind.WAVEFORM,TaskStatus.RUNNING,files=TaskProgress(20,240))
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()){
            TaskCenterContent(listOf(task),listOf(task),setOf(TaskOwner.WAVEFORM),{},{},{_,_->false})
        } }
        compose.onNodeWithText("Активные").performClick()
        compose.onNodeWithText("Генерация звуковых волн").assertExists()
        compose.onNodeWithText("20 из 240 треков").assertExists()
    }
    @Test fun lightHistoryShowsKnownCountsAfterExpansion(){
        val task=TaskSnapshot(taskId(TaskOwner.WAVEFORM,"end"),TaskKind.WAVEFORM,TaskStatus.COMPLETE,files=TaskProgress(20,20),updatedAt=200,
            details=TaskDetails(TaskCorrelation.create(),100,TaskTrigger.BACKGROUND,outcomes=TaskOutcomes(12,8)))
        compose.setContent { MaterialTheme(colorScheme=lightColorScheme()){
            TaskCenterContent(listOf(task),emptyList(),setOf(TaskOwner.WAVEFORM),{},{},{_,_->false})
        } }
        compose.onNodeWithText("История").performClick()
        compose.onNodeWithText("Генерация звуковых волн").performClick()
        compose.onNodeWithText("Создано:").assertExists()
        compose.onNodeWithText("Уже в кэше:").assertExists()
    }

    @Test fun partialHistoryAndLatestConfirmedResultRemainDistinctFromErrors(){
        val partial=TaskSnapshot(taskId(TaskOwner.WAVEFORM,"partial"),TaskKind.WAVEFORM,TaskStatus.PARTIAL,
            files=TaskProgress(2,2),updatedAt=100,details=TaskDetails(TaskCorrelation.create(),10,TaskTrigger.BACKGROUND,outcomes=TaskOutcomes(skipped=2)))
        val complete=TaskSnapshot(taskId(TaskOwner.WAVEFORM,"complete"),TaskKind.WAVEFORM,TaskStatus.COMPLETE,
            files=TaskProgress(20,20),updatedAt=50,details=TaskDetails(TaskCorrelation.create(),10,TaskTrigger.PERIODIC,outcomes=TaskOutcomes(20,0)))
        org.junit.Assert.assertEquals(partial,TaskCenterPresentation.latestConfirmed(listOf(partial,complete),TaskKind.WAVEFORM))
        org.junit.Assert.assertTrue(TaskCenterPresentation.errors(listOf(partial,complete)).isEmpty())
        compose.setContent { MaterialTheme(colorScheme=lightColorScheme()){
            TaskCenterContent(listOf(partial,complete),emptyList(),setOf(TaskOwner.WAVEFORM),{},{},{_,_->false})
        } }
        compose.onNodeWithText("История").performClick()
        compose.onNodeWithText("Частичные").performClick()
        compose.onNodeWithText("Частично завершено").assertExists()
        compose.onNodeWithText("2 из 2 треков").assertExists()
    }
}
