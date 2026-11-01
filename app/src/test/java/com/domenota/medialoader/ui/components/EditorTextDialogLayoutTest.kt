package com.domenota.medialoader.ui.components

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import com.domenota.medialoader.data.editor.TextLayer
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], qualifiers = "w384dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class EditorTextDialogLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun launch() {
        val image = Bitmap.createBitmap(384, 400, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MaterialTheme {
                EditorTextDialog(initial = listOf(TextLayer()), loadPreview = { image },
                    onApply = {}, onDismiss = {})
            }
        }
    }

    @Test fun selectedTextOpensIntegratedSheetWithKeyboardStyleAndFonts() {
        launch()
        compose.onNodeWithText("Все настройки текста").assertDoesNotExist()
        compose.onNodeWithText("Удалить надпись").assertDoesNotExist()
        // A tap on the selected object opens the very same bottom component.
        compose.onNodeWithContentDescription("Рамка надписи: перемещение, размер и поворот")
            .performTouchInput { click(center) }
        compose.onNodeWithText("Клавиатура").assertIsDisplayed()
        compose.onNodeWithText("Стиль").performClick()
        compose.onNodeWithText("Цвет текста").assertIsDisplayed()
        compose.onNodeWithContentDescription("Пипетка").assertExists()
        compose.onNodeWithText("Шрифт").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Библиотека шрифтов").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Библиотека шрифтов").assertExists()
    }

    @Test fun fullSheetDeleteRemainsInsidePhysicalScreen() {
        val resources = compose.activity.resources
        val screenHeight = resources.displayMetrics.heightPixels
        resources.configuration.screenHeightDp += 100
        launch()
        compose.onNodeWithText("Изменить").performClick()
        compose.onNodeWithText("Удалить надпись").performScrollTo().assertIsDisplayed()
        val bounds = compose.onNodeWithText("Удалить надпись").fetchSemanticsNode().boundsInWindow
        assertTrue("Delete clipped below screen: " + bounds + " / " + screenHeight,
            bounds.bottom <= screenHeight)
    }

    @Test fun draftAndFontTabSurviveOrientationChange() {
        launch()
        compose.onNodeWithText("Изменить").performClick()
        compose.onNodeWithText("Ваш текст").performTextReplacement("Черновик после поворота")
        compose.onNodeWithText("Шрифт").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Библиотека шрифтов").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnUiThread {
            RuntimeEnvironment.setQualifiers("w800dp-h384dp-land-xhdpi")
            val configuration = compose.activity.resources.configuration
            compose.activity.onConfigurationChanged(configuration)
            compose.activity.window.decorView.dispatchConfigurationChanged(configuration)
        }
        compose.onNodeWithText("Библиотека шрифтов").assertExists()
        compose.onNodeWithText("Клавиатура").performClick()
        compose.onNodeWithText("Черновик после поворота").assertExists()
        compose.onNodeWithText("Удалить надпись").performScrollTo().performClick()
        compose.onNodeWithText("Черновик после поворота").assertDoesNotExist()
    }
}
