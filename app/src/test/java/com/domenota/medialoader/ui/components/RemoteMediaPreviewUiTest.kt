package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.ui.model.*
import com.domenota.medialoader.ui.screens.PreviewScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteMediaPreviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun photo(id: String, selected: Boolean = false) = PreviewMediaUi(id, "Фото $id", "JPG", null,
        MediaKind.IMAGE, selected, providerId = "instagram")

    @Test fun photoOpeningNavigationAndClosingDoNotChangeSelectionOrDownload() {
        var items by mutableStateOf(listOf(photo("А", true), photo("Б")))
        var toggles = 0
        var downloads = 0
        compose.setContent {
            MaterialTheme {
                PreviewScreen("https://instagram.com/p/test", AnalysisUiState.PREVIEW_READY, items,
                    onBack = {}, onToggleItem = { id ->
                        toggles++; items = items.map { if (it.id == id) it.copy(selected = !it.selected) else it }
                    }, onDownloadSelected = { downloads++ }, asSheet = true)
            }
        }
        compose.onNodeWithContentDescription("Выбрать: Фото Б").performClick().assertIsOn()
        assertEquals(1, toggles)
        compose.onNodeWithText("Предпросмотр").assertDoesNotExist()
        compose.onNodeWithContentDescription("Предпросмотр: Фото Б").performClick()
        compose.onNodeWithText("Предпросмотр").assertIsDisplayed()
        compose.onNodeWithText("2 из 2").assertIsDisplayed()
        assertEquals(1, toggles)
        compose.onNodeWithContentDescription("Предыдущее фото").performClick()
        compose.onNodeWithText("1 из 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрыть предпросмотр").performClick()
        compose.onNodeWithText("Предпросмотр").assertDoesNotExist()
        compose.onNodeWithContentDescription("Выбрать: Фото Б").assertIsOn()
        assertEquals(0, downloads)
    }

    @Test fun videoCheckSelectsWhileCardOpensStreamAndFailureCanBeClosed() {
        val item = PreviewMediaUi("v", "Ролик", "720p", null, MediaKind.VIDEO, false, providerId = "youtube")
        var toggles = 0
        var resolved: String? = null
        compose.setContent {
            MaterialTheme {
                PreviewScreen("https://youtube.com/watch?v=test", AnalysisUiState.PREVIEW_READY, listOf(item),
                    onBack = {}, onToggleItem = { toggles++ }, asSheet = true,
                    resolvePlayback = { id -> resolved = id; throw IllegalArgumentException("test unavailable") })
            }
        }
        compose.onNodeWithContentDescription("Выбрать: Ролик").performClick()
        assertEquals(1, toggles)
        assertNull(resolved)
        compose.onNodeWithContentDescription("Предпросмотр: Ролик").performClick()
        compose.onNodeWithText("Предпросмотр").assertIsDisplayed()
        compose.waitUntil(10_000) { resolved == "v" }
        compose.onNodeWithText("Повторить").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрыть предпросмотр").performClick()
        compose.onNodeWithText("Предпросмотр").assertDoesNotExist()
        assertEquals(1, toggles)
    }

    @Test fun audioOpensAudioViewerAndRetryResolvesAgainWithoutSelecting() {
        val item = PreviewMediaUi("a", "Песня", "MP3", null, MediaKind.AUDIO, false, providerId = "instagram")
        var calls = 0
        var toggles = 0
        compose.setContent {
            MaterialTheme {
                PreviewScreen("https://instagram.com/p/test", AnalysisUiState.PREVIEW_READY, listOf(item),
                    onBack = {}, onToggleItem = { toggles++ }, asSheet = true,
                    resolvePlayback = { calls++; throw IllegalArgumentException("test unavailable") })
            }
        }
        compose.onNodeWithContentDescription("Предпросмотр: Песня").performClick()
        compose.onNodeWithText("Предпросмотр").assertIsDisplayed()
        compose.onNodeWithContentDescription("Аудио").assertExists()
        compose.onNodeWithText("Повторить").performClick()
        compose.waitUntil(10_000) { calls == 2 }
        assertEquals(0, toggles)
        compose.onNodeWithContentDescription("Закрыть предпросмотр").performClick()
        compose.onNodeWithText("Предпросмотр").assertDoesNotExist()
    }
}
