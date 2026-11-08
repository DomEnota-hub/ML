package com.domenota.medialoader.ui.components

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryMediaDeleteSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pluralCountIsWrittenOnceInBothPromptModes() {
        val cases = listOf(
            1 to "1 объект", 2 to "2 объекта", 3 to "3 объекта", 5 to "5 объектов",
            11 to "11 объектов", 21 to "21 объект", 24 to "24 объекта", 112 to "112 объектов")
        cases.forEach { (count, noun) ->
            assertEquals(noun, deleteMediaCountLabel(count))
            assertEquals(1, Regex("\\b$count\\s+объект").findAll(deleteMediaPrompt(count, false)).count())
            assertEquals(1, Regex("\\b$count\\s+объект").findAll(deleteMediaPrompt(count, true)).count())
        }
    }

    @Test fun defaultIsRecoverableTrashAndPermanentChoiceChangesThePrompt() {
        var permanent by mutableStateOf(false)
        var confirmed = false
        compose.setContent {
            MaterialTheme {
                LibraryMediaDeleteSheet(
                    count = 3, permanently = permanent,
                    onPermanentChange = { permanent = it },
                    onDismiss = {}, onDelete = { confirmed = true })
            }
        }
        compose.onNodeWithText("Удалить", useUnmergedTree = false).assertExists()
        compose.onNodeWithText("Переместить 3 объекта в корзину?", substring = true).assertExists()
        compose.onNodeWithText("Удалить навсегда").performClick()
        compose.waitForIdle()
        assertTrue(permanent)
        compose.onNodeWithText("Удалить 3 объекта навсегда?", substring = true).assertExists()
        assertFalse(confirmed)
        compose.onNodeWithText("Отмена").assertExists()
    }
}
