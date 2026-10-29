package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.library.AppMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryModeHeaderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun titleSwitchesBetweenAllThreeModes() {
        compose.setContent { MaterialTheme {
            var mode by remember { mutableStateOf(AppMode.DOWNLOADER) }
            AppModeTitle(mode) { mode = it }
        } }
        for (label in listOf("Галерея", "Музыка", "Загрузчик")) {
            compose.onNodeWithContentDescription("Сменить режим").performClick()
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithText(label).assertExists()
        }
    }
}
