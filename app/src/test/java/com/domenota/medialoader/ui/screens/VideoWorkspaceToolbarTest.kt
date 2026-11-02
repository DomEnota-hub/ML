package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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

    @Test fun rootLabelsExactlyMatchApprovedHtmlV8AndNeverOmitLongLabels() {
        assertEquals(
            listOf("Редактор", "Музыка", "Надписи", "Соотношение сторон",
                "Откорректировать", "Фильтры", "Ещё"),
            referenceRootLabels()
        )
        compose.setContent {
            MaterialTheme { VideoWorkspaceToolbar(null, {}, true) }
        }
        compose.onNodeWithText("Соотношение\nсторон").assertExists()
        compose.onNodeWithText("Откорректи\nровать").assertExists()
        compose.onNodeWithContentDescription("Ещё").assertExists()
    }

    @Test fun editorSectionOpensWithoutGroupChipsAndInvokesRuntimeTrim() {
        var selection: VideoWorkspaceTool? = null
        compose.setContent {
            MaterialTheme {
                VideoWorkspaceToolbar(selection, { selection = it }, true)
            }
        }
        compose.onNodeWithContentDescription("Редактор").performClick()
        compose.onNodeWithContentDescription("Обрезка").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(VideoWorkspaceTool.TRIM, selection) }
        compose.onNodeWithContentDescription("К главному меню").performClick()
        compose.runOnIdle { assertEquals(null, selection) }
    }

    @Test fun selectingActiveToolCollapsesItsPanel() {
        val selected = mutableStateOf<VideoWorkspaceTool?>(VideoWorkspaceTool.TRIM)
        compose.setContent {
            MaterialTheme { VideoWorkspaceToolbar(selected.value, { selected.value = it }, true) }
        }
        compose.onNodeWithContentDescription("Редактор").performClick()
        compose.onNodeWithContentDescription("Обрезка").performClick()
        compose.runOnIdle { assertEquals(null, selected.value) }
    }

    @Test fun sharedColorToolRespectsDirectCorrectionVsFilters() {
        val selection = mutableStateOf<VideoWorkspaceTool?>(null)
        val isManual = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                VideoWorkspaceToolbar(
                    selected = selection.value,
                    onSelect = { selection.value = it },
                    enabled = true,
                    onColorManualMode = { isManual.value = it },
                    colorManualMode = isManual.value,
                )
            }
        }
        compose.onNodeWithContentDescription("Откорректировать").performClick()
        compose.runOnIdle {
            assertEquals(VideoWorkspaceTool.COLOR, selection.value)
            assertEquals(true, isManual.value)
        }
        compose.onNodeWithContentDescription("Фильтры").performClick()
        compose.runOnIdle {
            assertEquals(VideoWorkspaceTool.COLOR, selection.value)
            assertEquals(false, isManual.value)
        }
    }
}
