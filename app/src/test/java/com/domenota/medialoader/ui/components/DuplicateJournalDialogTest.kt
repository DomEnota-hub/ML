package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class DuplicateJournalDialogTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun deleteOneCompletedRowOnlyAfterConfirmation() {
        var deleted:Long?=null
        compose.setContent {
            MaterialTheme {
                DuplicateJournalDialog("Журнал Музыки",
                    listOf(DuplicateJournalRow(1700000990000L,2,6),
                        DuplicateJournalRow(1700000991000L,4,10)),
                    DuplicateJournalRow(1700000992000L,1,2,"Незавершён"),
                    onOpen={},onResume={},onDismiss={},
                    onDelete={deleted=it},deleteEnabled=true)
            }
        }
        compose.onNodeWithTag("journal-delete-1700000990000").performClick()
        compose.onNodeWithText("Удалить выбранный отчёт?").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle{assertEquals(null,deleted)}
        compose.onNodeWithTag("journal-delete-1700000991000").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.runOnIdle{assertEquals(1700000991000L,deleted)}
        compose.onNodeWithTag("journal-delete-1700000990000").assertIsDisplayed()
    }
}
