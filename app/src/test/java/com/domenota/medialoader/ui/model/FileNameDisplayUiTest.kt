package com.domenota.medialoader.ui.model

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.ui.components.DownloadRow
import com.domenota.medialoader.ui.screens.HideExtensionsSetting
import com.domenota.medialoader.ui.screens.music.MusicLibraryTrackRow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileNameDisplayUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val prefs get() = compose.activity.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private fun audio(name:String, tag:String? = null) = DownloadEntity("audio-$name", "device", name,
        MediaType.AUDIO, DownloadState.COMPLETED, null, 123L, 42L, sourceAudioTitle = tag)

    @Test fun settingUpdatesRealRowsImmediatelyAndPreservesFormatAndRawModel() {
        prefs.edit().remove(FileNameDisplay.KEY).commit()
        val video = DownloadUiItem("video", "Иллюзия.mp4", "Видео · MP4", "8.2 МБ", state = DownloadUiState.COMPLETED, kind = MediaKind.VIDEO)
        val song = audio("live.2026.MP3")
        val tagged = audio("actual.mp3", "AC/DC — T.N.T.")
        compose.setContent { MaterialTheme { FileNameDisplayProvider { Column {
            HideExtensionsSetting()
            DownloadRow(video, onOpen = {}, onCancel = {})
            MusicLibraryTrackRow(song, onPlay = {}) { }
            MusicLibraryTrackRow(tagged, onPlay = {}) { }
        } } } }
        val toggle = compose.onNodeWithTag("hide-file-extensions", useUnmergedTree = true)
        fun text(value:String) = compose.onNodeWithText(value, useUnmergedTree = true)
        toggle.assertIsOff()
        text("Иллюзия.mp4").assertExists(); text("live.2026.MP3").assertExists()
        toggle.performClick().assertIsOn()
        text("Иллюзия").assertExists(); text("live.2026").assertExists()
        text("Иллюзия.mp4").assertDoesNotExist(); text("live.2026.MP3").assertDoesNotExist()
        text("MP4 · 8.2 МБ").assertExists(); text("AC/DC — T.N.T.").assertExists()
        compose.runOnIdle { assertTrue(FileNameDisplay.read(prefs)); assertEquals("Иллюзия.mp4", video.title); assertEquals("live.2026.MP3",song.originalName) }
        toggle.performClick().assertIsOff()
        text("Иллюзия.mp4").assertExists(); text("live.2026.MP3").assertExists()
        compose.runOnIdle { assertFalse(FileNameDisplay.read(prefs)) }
    }
    @Test fun storedPreferenceAppliedAtNewCompositionAndExternalChangeObserved() {
        prefs.edit().putBoolean(FileNameDisplay.KEY,true).commit()
        compose.setContent { MaterialTheme { FileNameDisplayProvider { Column {
            HideExtensionsSetting()
            MusicLibraryTrackRow(audio("saved.mp3"), onPlay = {}) { }
        } } } }
        compose.onNodeWithTag("hide-file-extensions",useUnmergedTree=true).assertIsOn()
        compose.onNodeWithText("saved",useUnmergedTree=true).assertExists()
        compose.runOnIdle { prefs.edit().putBoolean(FileNameDisplay.KEY,false).commit() }
        compose.onNodeWithTag("hide-file-extensions",useUnmergedTree=true).assertIsOff()
        compose.onNodeWithText("saved.mp3",useUnmergedTree=true).assertExists()
    }
}
