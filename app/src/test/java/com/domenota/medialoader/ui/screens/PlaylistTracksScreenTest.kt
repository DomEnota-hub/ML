package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.ui.screens.music.PlaylistTracksScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaylistTracksScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = track("app", "App")
    private val phone = track("phone:audio:42", "Phone")

    @Test fun sourceSwitchAndPreviewKeepTheSelectionAndConfirmOnce() {
        var source by mutableStateOf(LibrarySource.APP)
        var preview: Pair<String, List<String>>? = null
        var added = emptyList<String>()
        var closed = 0
        compose.setContent { MaterialTheme {
            PlaylistTracksScreen(MediaPlaylist("p", "Mix", emptyList()), source, { source = it },
                listOf(app), listOf(app, phone), emptyMap(), { closed++ },
                { id, ids -> preview = id to ids }, { added = it })
        } }
        compose.onNodeWithContentDescription("Добавить App").performClick()
        compose.onNodeWithContentDescription("Источник файлов").performClick()
        compose.onNodeWithText("Все файлы телефона").performClick()
        compose.onNodeWithContentDescription("Добавить Phone").performClick()
        compose.onNodeWithText("Phone").performClick()
        assertEquals("phone:audio:42" to listOf("app", "phone:audio:42"), preview)
        compose.onNodeWithText("Добавить · 2").performClick()
        compose.waitForIdle()
        assertEquals(listOf("app", "phone:audio:42"), added)
        assertEquals(1, closed)
    }

    @Test fun existingAndPrivateTracksCannotBecomeNewSelection() {
        val private = track("private", "Secret").copy(isPrivate = true)
        compose.setContent { MaterialTheme {
            PlaylistTracksScreen(MediaPlaylist("p", "Mix", listOf("app")), LibrarySource.PHONE, {},
                listOf(app), listOf(app, private), emptyMap(), {}, { _, _ -> }, {})
        } }
        compose.onNodeWithContentDescription("В плейлисте App").assertIsNotEnabled()
        compose.onNodeWithText("Secret").assertDoesNotExist()
        compose.onNodeWithText("Добавить · 0").assertIsNotEnabled()
    }

    @Test fun selectedTracksSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme {
            PlaylistTracksScreen(MediaPlaylist("p", "Mix", emptyList()), LibrarySource.APP, {},
                listOf(app), listOf(app, phone), emptyMap(), {}, { _, _ -> }, {})
        } }
        compose.onNodeWithContentDescription("Добавить App").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Добавить · 1").assertIsEnabled()
    }

    private fun track(id: String, title: String) = DownloadEntity(id, "device", title, MediaType.AUDIO,
        DownloadState.COMPLETED, "content://media/$id", null, 0, sourceAudioTitle = title)
}
