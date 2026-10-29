package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.ui.screens.gallery.GalleryMediaViewer
import java.io.File
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
class GalleryViewerPhotoUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun deletingCurrentPhotoPublishesNeighborAndKeepsItAfterAnotherQueueUpdate() {
        val first = photo("First")
        val middle = photo("Middle")
        val last = photo("Last")
        val newest = photo("New")
        var rows by mutableStateOf(listOf(first, middle, last))
        var current: String? = null
        compose.setContent { MaterialTheme { Viewer(middle, rows) { current = it.id } } }
        compose.waitForIdle()
        assertEquals("Middle", current)
        compose.runOnIdle { rows = listOf(first, last) }
        compose.waitForIdle()
        assertEquals("Last", current)
        compose.runOnIdle { rows = listOf(first, last, newest) }
        compose.waitForIdle()
        assertEquals("Last", current)
    }

    @Test fun photoTapHidesAndRestoresPanelsAndSwipeChangesFileWithoutArrows() {
        val first = photo("First")
        val last = photo("Last")
        var current: String? = null
        compose.setContent { MaterialTheme { Viewer(first, listOf(first, last)) { current = it.id } } }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("First").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Поделиться").assertExists()
        compose.onNodeWithContentDescription("First").performTouchInput { click() }
        // A single tap is delivered after the double-tap recognition window.
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithContentDescription("Поделиться").assertDoesNotExist()
        compose.onNodeWithContentDescription("First").performTouchInput { advanceEventTime(400); click() }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithContentDescription("Поделиться").assertExists()
        compose.onNodeWithContentDescription("Предыдущий файл").assertDoesNotExist()
        compose.onNodeWithContentDescription("Следующий файл").assertDoesNotExist()
        compose.onNodeWithContentDescription("First").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals("Last", current)
    }

    @Composable private fun Viewer(item: DownloadEntity, rows: List<DownloadEntity>, onCurrent: (DownloadEntity) -> Unit) {
        GalleryMediaViewer(item, rows, {}, {}, {}, {}, emptySet(), { _, _ -> }, emptyList(), { _, _ -> }, {}, {}, onCurrent)
    }

    private fun photo(id: String): DownloadEntity {
        val file = File(compose.activity.cacheDir, "viewer-$id.png")
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return DownloadEntity(id, "device", id, MediaType.PHOTO, DownloadState.COMPLETED,
            Uri.fromFile(file).toString(), file.length(), 1_000_000)
    }
}
