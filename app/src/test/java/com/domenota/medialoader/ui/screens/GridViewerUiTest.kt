package com.domenota.medialoader.ui.screens

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.ui.model.*
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
class GridViewerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun gridOpeningPassesVisibleOrderAcrossGroups() {
        compose.activity.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("downloads_grid", true).commit()
        val rows = listOf("a", "b", "c").map { DownloadUiItem(it, "$it.jpg", "Фото", null,
            state = DownloadUiState.COMPLETED, groupId = if (it == "b") "other" else "post") }
        var opened = ""
        var ids = emptyList<String>()
        compose.setContent { MaterialTheme { DownloadsScreen(rows,
            onOpenGrid = { id, queue -> opened = id; ids = queue },
            onOpenDownload = { fail("Grid should pass its queue") }, onCancelDownload = {},
            onShareDownload = {}, onHideDownload = {}, onRetryDownload = {},
            onReanalyzeMissing = {}, onRenameDownload = { _, _ -> }) } }
        compose.onNodeWithContentDescription("b.jpg").performScrollTo().performClick()
        assertEquals("b", opened)
        // Grid groups are flattened in their displayed order, not raw input order.
        assertEquals(listOf("a", "c", "b"), ids)
    }

    @Test fun privateGridPassesItsOwnOrderOnly() {
        val context = compose.activity.applicationContext
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("private_grid", true).commit()
        context.getSharedPreferences("private_access", Context.MODE_PRIVATE).edit().clear().commit()
        val access = com.domenota.medialoader.data.privatevault.PrivateAccessManager::class.java
            .getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        kotlinx.coroutines.runBlocking { access.configure(com.domenota.medialoader.data.privatevault.PrivateAccessMode.NONE) }
        val rows = listOf("p3", "p1", "p2").map { DownloadEntity(it, "test", "$it.jpg", MediaType.PHOTO,
            DownloadState.COMPLETED, null, null, 0, isPrivate = true, groupId = it) }
        var ids = emptyList<String>()
        compose.setContent { MaterialTheme { PrivateScreen(access, rows, {}, {}, {}, {}, {}, { _, _ -> },
            onOpenGrid = { _, queue -> ids = queue }) } }
        compose.onNodeWithContentDescription("p1.jpg").performClick()
        assertEquals(listOf("p3", "p1", "p2"), ids)
    }

    @Test fun consecutiveVideosKeepVisibilityCallbacksConnected() {
        val rows = (1..2).map { DownloadEntity("video-$it", "test", "video-$it", MediaType.VIDEO,
            DownloadState.COMPLETED, "file:///missing-$it.mp4", null, 0) }
        compose.setContent { MaterialTheme { MediaViewerScreen(rows.first(), {}, {}, {}, mediaQueue = rows) } }
        compose.onNodeWithContentDescription("Следующий файл").performClick()
        compose.onNodeWithContentDescription("Настройки плеера").assertExists()
        // Drive the real native controller: Robolectric has no rendered video surface for touch routing.
        compose.runOnIdle {
            fun findPlayer(view: android.view.View): androidx.media3.ui.PlayerView? {
                if (view is androidx.media3.ui.PlayerView) return view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                    findPlayer(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            val native = requireNotNull(findPlayer(compose.activity.window.decorView))
            native.showController()
            native.hideController()
        }
        compose.onNodeWithContentDescription("Настройки плеера").assertDoesNotExist()
    }

    @Test fun mixedQueueUpdatesActionsAndRestoresCurrentFile() {
        val rows = listOf(MediaType.PHOTO, MediaType.VIDEO, MediaType.PHOTO).mapIndexed { index, type ->
            DownloadEntity("$index", "test", "file-$index", type, DownloadState.COMPLETED,
                "file:///nonexistent-$index", null, 0) }
        var edited = ""
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { MediaViewerScreen(rows.first(), {}, {}, {},
            mediaQueue = rows, onEdit = { edited = it.id }) } }
        compose.onNodeWithContentDescription("Предыдущий файл").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Следующий файл").performClick()
        compose.onNodeWithText("file-1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Редактировать").performClick()
        assertEquals("1", edited)
        compose.onNodeWithContentDescription("Следующий файл").performClick()
        compose.onNodeWithText("file-2").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("file-2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Следующий файл").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Предыдущий файл").performClick()
        compose.onNodeWithText("file-1").assertIsDisplayed()
    }
}
