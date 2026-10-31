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

    @Test fun deleteIsVisibleBeforeAndAfterScrollingToLastFont() {
        val image = Bitmap.createBitmap(384, 400, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MaterialTheme {
                EditorTextDialog(initial = listOf(TextLayer()), loadPreview = { image },
                    onApply = {}, onDismiss = {})
            }
        }
        // The default UX keeps the preview large and advanced controls collapsed.
        compose.onNodeWithText("Удалить надпись").assertDoesNotExist()
        compose.onNodeWithText("Все настройки текста").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("DejaVu Sans · очень тонкий").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(isRoot()).printToLog("EDITOR_LAYOUT")
        compose.onNodeWithText("Удалить надпись").assertIsDisplayed()
        compose.onNodeWithText("DejaVu Sans · очень тонкий").performScrollTo()
        compose.onNodeWithText("Удалить надпись").assertIsDisplayed()
        compose.onAllNodes(isRoot()).printToLog("EDITOR_LAYOUT")
    }

    @Test fun deleteStaysInsidePhysicalDisplayWhenConfigurationHeightIsLarger() {
        val resources = compose.activity.resources
        val screenHeight = resources.displayMetrics.heightPixels
        // Older Compose DialogLayout uses this value instead of the actual window measure spec.
        resources.configuration.screenHeightDp += 100
        val image = Bitmap.createBitmap(384, 400, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MaterialTheme {
                EditorTextDialog(initial = listOf(TextLayer()), loadPreview = { image },
                    onApply = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("Все настройки текста").performClick()
        val bounds = compose.onNodeWithText("Удалить надпись").fetchSemanticsNode().boundsInWindow
        println("DELETE: $bounds, physical height: $screenHeight")
        assertTrue("Delete extends beyond physical display: $bounds / $screenHeight", bounds.bottom <= screenHeight)
    }

    @Test fun draftAndSelectedFontRemainOpenAcrossOrientationConfigurationChange() {
        val image = Bitmap.createBitmap(384, 400, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MaterialTheme {
                EditorTextDialog(initial = listOf(TextLayer()), loadPreview = { image },
                    onApply = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("Все настройки текста").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Noto Sans").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Ваш текст").performTextReplacement("Черновик после поворота")
        compose.onNodeWithText("Noto Sans").performScrollTo().performClick()
        compose.runOnUiThread {
            RuntimeEnvironment.setQualifiers("w800dp-h384dp-land-xhdpi")
            val configuration = compose.activity.resources.configuration
            compose.activity.onConfigurationChanged(configuration)
            compose.activity.window.decorView.dispatchConfigurationChanged(configuration)
        }
        compose.onNodeWithText("✓ Noto Sans").assertExists()
        compose.onNodeWithText("Черновик после поворота").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Удалить надпись").assertIsDisplayed().performClick()
        compose.onNodeWithText("Черновик после поворота").assertDoesNotExist()
    }
}
