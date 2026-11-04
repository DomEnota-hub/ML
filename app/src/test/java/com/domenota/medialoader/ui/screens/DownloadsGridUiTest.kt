package com.domenota.medialoader.ui.screens

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.ui.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DownloadsGridUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun reset() { compose.activity.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun gridHasFourColumnsNoTitlesAndKeepsFileActions() {
        val photos = (1..5).map { DownloadUiItem(id = "$it", title = "Фото $it", subtitle = "Фото", sizeLabel = null,
            state = DownloadUiState.COMPLETED, kind = MediaKind.IMAGE) }
        var opened = ""
        compose.setContent { MaterialTheme { DownloadsScreen(photos, onOpenDownload = { opened = it }, onCancelDownload = {},
            onShareDownload = {}, onHideDownload = {}, onRetryDownload = {}, onReanalyzeMissing = {}, onRenameDownload = { _, _ -> }) } }
        compose.onNodeWithContentDescription("Показать сеткой").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Фото 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Фото 1").assertDoesNotExist()
        val bounds = (1..5).map { compose.onNodeWithContentDescription("Фото $it").fetchSemanticsNode().boundsInRoot }
        assertEquals(bounds[0].top, bounds[3].top, 1f)
        assertTrue(bounds[3].left > bounds[0].left)
        assertTrue(bounds[4].top > bounds[0].top)
        assertEquals(bounds[0].width, bounds[4].width, 1f)
        compose.onNodeWithContentDescription("Фото 1").performClick()
        assertEquals("1", opened)
        compose.onNodeWithContentDescription("Фото 1").performTouchInput { longClick() }
        compose.onNodeWithText("Переместить в личное").assertIsDisplayed()
        assertTrue(compose.activity.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("downloads_grid", false))
    }
    @Test fun selectionKeepsFourColumnGridAndPreviewDoesNotChangeChecks() {
        val photos=(1..5).map { DownloadUiItem(id="$it",title="Фото $it",subtitle="Фото",sizeLabel=null,
            state=DownloadUiState.COMPLETED,kind=MediaKind.IMAGE,savedUri="content://media/$it") }
        var opened=""
        compose.setContent { MaterialTheme { DownloadsScreen(photos,onOpenDownload={opened=it},onCancelDownload={},
            onShareDownload={},onHideDownload={},onRetryDownload={},onReanalyzeMissing={},onRenameDownload={_,_->}) } }
        compose.onNodeWithContentDescription("Показать сеткой").performScrollTo().performClick()
        compose.onNodeWithText("Выбрать").performScrollTo().performClick()
        val first=compose.onNodeWithContentDescription("Фото 1")
        first.performScrollTo()
        val a=first.fetchSemanticsNode().boundsInRoot
        val b=compose.onNodeWithContentDescription("Фото 4").fetchSemanticsNode().boundsInRoot
        assertEquals(a.top,b.top,1f);assertTrue(b.left>a.left)
        compose.onNodeWithContentDescription("Выбрать Фото 1").performClick()
        compose.onNodeWithText("Выбрано: 1").assertExists()
        first.performTouchInput { click(bottomRight-androidx.compose.ui.geometry.Offset(5f,5f)) }
        assertEquals("1",opened)
        compose.onNodeWithText("Выбрано: 1").assertExists()
        compose.onNodeWithContentDescription("Выбрать Фото 1").assertIsOn()
        compose.onNodeWithText("Отмена").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Показать списком").assertExists()
    }
}
