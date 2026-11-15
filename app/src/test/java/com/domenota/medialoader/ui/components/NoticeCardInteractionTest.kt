package com.domenota.medialoader.ui.components

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h800dp-xhdpi")
class NoticeCardInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun closeAndBothHorizontalSwipesDismissOnlyTheCurrentCard() {
        var active by mutableStateOf<NoticeEntry?>(null)
        val dismissed = mutableListOf<Long>()
        fun entry(id: Long) = NoticeEntry(id, NoticeMessage("Notice $id"), SystemClock.uptimeMillis() - 400, 60000)
        active = entry(1)
        compose.setContent { MaterialTheme {
            active?.let { value -> key(value.id) {
                NoticeCard(value, null) { id -> dismissed += id; active = null }
            } }
        } }
        compose.onNodeWithContentDescription("Закрыть уведомление").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("ml-active-notice").assertDoesNotExist()
        compose.runOnIdle { active = entry(2) }
        compose.onNodeWithTag("ml-active-notice").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("ml-active-notice").assertDoesNotExist()
        compose.runOnIdle { active = entry(3) }
        compose.onNodeWithTag("ml-active-notice").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("ml-active-notice").assertDoesNotExist()
        assertEquals(listOf(1L, 2L, 3L), dismissed)
    }
}
