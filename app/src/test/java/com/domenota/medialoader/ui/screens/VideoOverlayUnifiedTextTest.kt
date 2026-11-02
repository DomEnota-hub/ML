package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.VideoGraphicLayer
import com.domenota.medialoader.data.editor.VideoGraphicKind
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.editor.TextLayer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class VideoOverlayUnifiedTextTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun videoTextSharesTheSameKeyboardStyleAndFontComponentAsPhotos() {
        val layer = TextLayer(text = "Видеонадпись", endMs = 9000L)
        var edited by mutableStateOf(layer)
        compose.setContent {
            MaterialTheme {
                VideoOverlayPanel(
                    graphicsTab = false, durationMs = 10_000L, playheadMs = 0L,
                    texts = listOf(edited), graphics = emptyList(), selectedId = edited.id,
                    enabled = true, onSelect = {}, onAddText = {}, onAddGraphic = {},
                    onAddImage = {}, onChangeText = { edited = it },
                    onChangeGraphic = {}, onRemoveText = {}, onRemoveGraphic = {},
                    onSeek = {}
                )
            }
        }
        compose.onNodeWithText("Клавиатура").assertExists()
        compose.onNodeWithText("Стиль").performClick()
        compose.onNodeWithText("Цвет текста").assertExists()
        compose.onNodeWithText("Обводка").assertExists()
        compose.onNodeWithText("Шрифт").performClick()
        compose.onNodeWithText("Библиотека шрифтов").assertExists()
        compose.onNodeWithText("Клавиатура").performClick()
        compose.onNodeWithText("Видеонадпись").assertExists()
    }
    @Test fun textAndStickersCanOpenInsideProductionScrollSheet() {
        var graphics by mutableStateOf(false)
        val textLayer = TextLayer(text = "Слой", endMs = 9000L)
        val sticker = VideoGraphicLayer(kind = VideoGraphicKind.STAR, endMs = 9000L)
        compose.setContent {
            MaterialTheme {
                // Mirror the parent VideoEditorScreen tool-sheet constraint.
                Column(Modifier.height(218.dp).verticalScroll(rememberScrollState())) {
                    VideoOverlayPanel(
                        graphicsTab = graphics, durationMs = 10_000L, playheadMs = 0L,
                        texts = listOf(textLayer), graphics = listOf(sticker),
                        selectedId = if (graphics) sticker.id else textLayer.id,
                        enabled = true, onSelect = {}, onAddText = {}, onAddGraphic = {},
                        onAddImage = {}, onChangeText = {}, onChangeGraphic = {},
                        onRemoveText = {}, onRemoveGraphic = {}, onSeek = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Надписи").assertExists()
        compose.runOnUiThread { graphics = true }
        compose.waitForIdle()
        compose.onNodeWithText("Графика и стикеры").assertExists()
        compose.onNodeWithText("Размер").assertExists()
    }

}
