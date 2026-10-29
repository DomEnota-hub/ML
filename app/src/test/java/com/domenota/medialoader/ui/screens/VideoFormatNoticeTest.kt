package com.domenota.medialoader.ui.screens

import android.os.Looper
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class VideoFormatNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun advance(milliseconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }
    @Test fun latestFormatReplacesImmediatelyAndRestartsTimeout() {
        val notice = mutableStateOf("Вписать" to 1L)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { VideoFormatNotice(notice.value.first, notice.value.second) } }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        advance(800)
        compose.onNodeWithText("Вписать").assertIsDisplayed()
        compose.runOnIdle { notice.value = "Растянуть" to 2L }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        advance(600)
        compose.onNodeWithText("Вписать").assertDoesNotExist()
        compose.onNodeWithText("Растянуть").assertIsDisplayed()
        advance(800)
        compose.onNodeWithText("Растянуть").assertDoesNotExist()
    }
    @Test fun cyclingBackToTheSameFormatStillRefreshesTheNotice() {
        val request = mutableStateOf(1L)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { VideoFormatNotice("Вписать", request.value) } }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        advance(800)
        compose.runOnIdle { request.value++ }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        advance(600)
        compose.onNodeWithText("Вписать").assertIsDisplayed()
        advance(800)
        compose.onNodeWithText("Вписать").assertDoesNotExist()
    }

}
