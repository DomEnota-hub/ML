package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryRefreshShieldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun refreshNeverCoversGalleryOrInterruptsEditableState() {
        val loading = mutableStateOf(false)
        compose.setContent { MaterialTheme {
            LibraryRefreshShield(loading.value) {
                var draft by remember { mutableStateOf("original") }
                Button(onClick = { draft = "unsaved text" }) { Text(draft) }
            }
        } }
        compose.onNodeWithText("original").performClick()
        compose.onNodeWithText("unsaved text").assertIsDisplayed()
        compose.runOnIdle { loading.value = true }
        compose.onNodeWithText("unsaved text").assertIsDisplayed()
        compose.onNodeWithText("Проверяем файлы").assertDoesNotExist()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithText("unsaved text").assertIsDisplayed()
    }

    @Test fun refreshDoesNotDisplayExitOrBlockInteractiveContent() {
        var taps = 0
        var exited = false
        compose.setContent { MaterialTheme {
            LibraryRefreshShield(true, onExit = { exited = true }) {
                Button(onClick = { taps++ }) { Text("cached gallery") }
            }
        } }
        compose.onNodeWithText("cached gallery").assertIsDisplayed().performClick()
        compose.onNodeWithText("Вернуться в загрузчик").assertDoesNotExist()
        compose.onNodeWithText("Проверяем файлы").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, taps)
            assertFalse(exited)
        }
    }
}
