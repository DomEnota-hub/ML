package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.ui.model.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DownloadVideoSongMenuUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun manualVideoLookupIsAvailableWithAutomaticRecognitionDisabled() {
        val video = DownloadUiItem("video", "movie.mp4", "", null, state = DownloadUiState.COMPLETED,
            kind = MediaKind.VIDEO, savedUri = "content://media/external/video/media/1")
        compose.setContent { MaterialTheme { DownloadRow(video, {}, {}, recognitionEnabled = false) } }
        compose.onNodeWithContentDescription("Действия с файлом").performClick()
        compose.onNodeWithText("Найти трек").assertIsDisplayed()
        compose.onNodeWithText("Найти название трека").assertDoesNotExist()
    }
    @Test fun photoMenuHasNoVideoRecognitionAction() {
        val photo = DownloadUiItem("photo", "picture.jpg", "", null, state = DownloadUiState.COMPLETED,
            kind = MediaKind.IMAGE, savedUri = "content://media/external/images/media/1")
        compose.setContent { MaterialTheme { DownloadRow(photo, {}, {}) } }
        compose.onNodeWithContentDescription("Действия с файлом").performClick()
        compose.onNodeWithText("Найти трек").assertDoesNotExist()
    }
}
