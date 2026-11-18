package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.catalog.visibility.VisibilityExecutionPhase
import com.domenota.medialoader.data.catalog.visibility.VisibilityExecutionSnapshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w384dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class FolderVisibilityTransferComposeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun state(phase:VisibilityExecutionPhase) =
        VisibilityExecutionSnapshot(phase=phase,confirmed=11,total=46,hasPendingJournal=true)

    @Test fun expandedDialogKeepsTwoLevelHeaderProgressStatusAndRealPauseButton() {
        var paused=false
        var collapsed=false
        compose.setContent { MaterialTheme {
            FolderVisibilityTransferDialog(
                state(VisibilityExecutionPhase.RUNNING),"Мелкие",
                onCollapse={collapsed=true},onPause={paused=true},onResume={},onDone={})
        } }
        compose.onNodeWithTag("visibility-transfer-expanded").assertIsDisplayed()
        compose.onNodeWithText("Перенос файлов").assertIsDisplayed()
        compose.onNodeWithText("Мелкие").assertIsDisplayed()
        compose.onNodeWithText("11 / 46").assertIsDisplayed()
        compose.onNodeWithText("24%").assertIsDisplayed()
        compose.onNodeWithText("Перенос выполняется").assertIsDisplayed()
        compose.onNodeWithText("Пауза").performClick()
        compose.runOnIdle { assertTrue(paused) }
        compose.onNodeWithContentDescription("Свернуть окно").performClick()
        compose.runOnIdle { assertTrue(collapsed) }
    }

    @Test fun waitingForAndroidHasNoClickableFakeResumeOrDelete() {
        compose.setContent { MaterialTheme {
            FolderVisibilityTransferDialog(state(VisibilityExecutionPhase.WAITING_FOR_ANDROID),
                null,onCollapse={},onPause={},onResume={},onDone={})
        } }
        compose.onNodeWithText("Требуется подтверждение Android").assertIsDisplayed()
        compose.onNodeWithText("Ожидание").assertIsNotEnabled()
        compose.onNodeWithText("Отмена").assertDoesNotExist()
        compose.onNodeWithText("Удалить").assertDoesNotExist()
    }

    @Test fun compactPanelDoesNotExposePrivateFolderNameAndReopens() {
        var reopened=false
        compose.setContent { MaterialTheme {
            FolderVisibilityMiniPanel(state(VisibilityExecutionPhase.RUNNING),
                onOpen={reopened=true})
        } }
        compose.onNodeWithTag("visibility-transfer-compact").assertIsDisplayed()
        compose.onNodeWithText("Перенос выполняется • 11/46").assertIsDisplayed()
        compose.onNodeWithText("Мелкие").assertDoesNotExist()
        compose.onNodeWithText("Открыть").performClick()
        compose.runOnIdle { assertTrue(reopened) }
    }
}
