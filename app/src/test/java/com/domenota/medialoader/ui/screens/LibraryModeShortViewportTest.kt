package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.AppMode
import com.domenota.medialoader.data.library.LibrarySource
import com.domenota.medialoader.ui.components.LibraryModeHeader
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Real mode controls share the scroll viewport left by landscape system/bottom bars. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryModeShortViewportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun galleryAppFileRemainsReachableBelowRealHeader() = gallery(LibrarySource.APP)
    @Test fun galleryPhoneFileRemainsReachableBelowRealHeader() = gallery(LibrarySource.PHONE)
    @Test fun musicAppTrackRemainsReachableBelowRealHeader() = music(LibrarySource.APP)
    @Test fun musicPhoneTrackRemainsReachableBelowRealHeader() = music(LibrarySource.PHONE)

    private fun gallery(source: LibrarySource) {
        val item = file("photo", "Photo.jpg", MediaType.PHOTO)
        var opened: Pair<String, List<String>>? = null
        compose.setContent { MaterialTheme { Box(Modifier.height(232.dp)) {
            GalleryModeScreen(listOf(item), source, false, null, emptyList(), emptyList(),
                { LibraryModeHeader(AppMode.GALLERY, source, {}, {}, {}, {}) },
                { id, ids -> opened = id to ids }, {}, { _, _ -> }, { _, _ -> }, { "folder" }, {}, {}, { _, _ -> }, {})
        } } }
        compose.onNodeWithTag("gallery-library").performScrollToNode(hasContentDescription("Photo.jpg"))
        compose.onNodeWithContentDescription("Photo.jpg").assertIsDisplayed().performClick()
        assertEquals("photo" to listOf("photo"), opened)
    }

    private fun music(source: LibrarySource) {
        val track = file("audio", "Track.mp3", MediaType.AUDIO)
        var opened: Pair<String, List<String>>? = null
        compose.setContent { MaterialTheme { Box(Modifier.height(232.dp)) {
            MusicModeScreen(listOf(track), listOf(track), emptyList(),
                { LibraryModeHeader(AppMode.MUSIC, source, {}, {}, {}, {}) }, false, null,
                { id, ids -> opened = id to ids }, {}, { "new" }, { _, _ -> }, {}, { _, _ -> },
                { _, _ -> }, { _, _, _ -> }, {}, {})
        } } }
        compose.onNodeWithTag("music-library").performScrollToNode(hasContentDescription("Действия: Track.mp3"))
        compose.onNodeWithContentDescription("Действия: Track.mp3").assertIsDisplayed().performClick()
        compose.onNodeWithText("Открыть").performClick()
        assertEquals("audio" to listOf("audio"), opened)
    }

    private fun file(id: String, name: String, type: MediaType) = DownloadEntity(
        id, "device", name, type, DownloadState.COMPLETED, null, null, 0)
}
