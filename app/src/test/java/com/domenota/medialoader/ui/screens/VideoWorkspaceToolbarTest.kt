package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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
class VideoWorkspaceToolbarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun everyToolIsRepresentedByARealAction() {
        var selection: VideoWorkspaceTool? = null
        compose.setContent {
            MaterialTheme {
                VideoWorkspaceToolbar(selection, { selection = it }, true)
            }
        }
        compose.onNodeWithContentDescription("Обрезка").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(VideoWorkspaceTool.TRIM, selection) }
    }

    @Test fun selectingActiveToolCollapsesItsPanel() {
        val selected = mutableStateOf<VideoWorkspaceTool?>(VideoWorkspaceTool.TRIM)
        compose.setContent {
            MaterialTheme {
                VideoWorkspaceToolbar(selected.value, { selected.value = it }, true)
            }
        }
        compose.onNodeWithContentDescription("Обрезка").performClick()
        compose.runOnIdle { assertEquals(null, selected.value) }
    }
}
