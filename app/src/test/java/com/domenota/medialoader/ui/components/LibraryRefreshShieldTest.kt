package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
class LibraryRefreshShieldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun refreshHidesContentWithoutDiscardingUnsavedEditorState() {
        val loading = mutableStateOf(false)
        compose.setContent { MaterialTheme { LibraryRefreshShield(loading.value) {
            var draft by remember { mutableStateOf("original") }
            Button(onClick = { draft = "unsaved text" }) { Text(draft) }
        } } }
        compose.onNodeWithText("original").performClick()
        compose.onNodeWithText("unsaved text").assertExists()
        compose.runOnIdle { loading.value = true }
        compose.onNodeWithText("unsaved text").assertDoesNotExist()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithText("unsaved text").assertExists()
        compose.onNodeWithText("original").assertDoesNotExist()
    }
    @Test fun loadingOffersAnExitWithoutPublishingUnvalidatedContent() {
        var exited = false
        compose.setContent { MaterialTheme {
            LibraryRefreshShield(true, onExit = { exited = true }) { Text("stale private candidate") }
        } }
        compose.onNodeWithText("stale private candidate").assertDoesNotExist()
        compose.onNodeWithText("Вернуться в загрузчик").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(exited) }
    }

}
