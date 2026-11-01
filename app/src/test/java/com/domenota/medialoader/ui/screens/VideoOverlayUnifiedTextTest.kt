package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
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
}
