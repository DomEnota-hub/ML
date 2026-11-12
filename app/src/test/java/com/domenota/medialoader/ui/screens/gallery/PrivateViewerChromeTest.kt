package com.domenota.medialoader.ui.screens.gallery

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PrivateViewerChromeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun actualPrivatePhotoViewerKeepsItsAuthorizedQueueOpen() {
        val context = compose.activity
        val file = java.io.File.createTempFile("vault-viewer-", ".png", context.cacheDir)
        val image = android.graphics.Bitmap.createBitmap(12, 12, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(android.graphics.Color.WHITE)
            file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val photo = com.domenota.medialoader.core.database.DownloadEntity(
                "private-test-photo", "instagram", "private.png",
                com.domenota.medialoader.core.model.MediaType.PHOTO,
                com.domenota.medialoader.core.model.DownloadState.COMPLETED,
                android.net.Uri.fromFile(file).toString(), file.length(), 1L,
            ).copy(isPrivate = true)
            var closed = false
            compose.setContent {
                MaterialTheme {
                    GalleryMediaViewer(photo, listOf(photo), onBack = { closed = true },
                        onShare = {}, onEdit = {}, onDelete = {}, favoriteIds = emptySet(),
                        onFavorite = { _, _ -> }, folders = emptyList(), onSetFolder = { _, _ -> },
                        onMoveToPrivate = {}, onOpenExternal = {}, onCurrentItemChanged = {},
                        privateMode = true)
                }
            }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Поделиться").assertExists()
            compose.onNodeWithContentDescription("Избранное").assertDoesNotExist()
            compose.runOnIdle { org.junit.Assert.assertFalse("Authorized vault viewer must not pop itself", closed) }
        } finally { image.recycle(); file.delete() }
    }

    @Test fun privatePhotoHasSameViewerActionsExceptHeart() {
        compose.setContent {
            MaterialTheme {
                GalleryV10Bottom(
                    isPhoto = true, isFavorite = false,
                    onShare = {}, onEdit = {}, onFavorite = {},
                    onDelete = {}, onMenu = {}, showFavorite = false,
                )
            }
        }
        compose.onNodeWithContentDescription("Избранное").assertDoesNotExist()
        compose.onNodeWithContentDescription("Поделиться").assertExists()
        compose.onNodeWithContentDescription("Редактировать").assertExists()
        compose.onNodeWithContentDescription("Удалить").assertExists()
        compose.onNodeWithContentDescription("Действия с файлом").assertExists()
    }

    @Test fun privateLandscapeVideoHasSameHeaderActionsExceptHeart() {
        compose.setContent {
            MaterialTheme {
                GalleryVideoHeader(
                    date = "11 ноября 2026", time = "18:00",
                    landscape = true,
                    onBack = {}, onRotate = {}, onFloating = {},
                    onShare = {}, onEdit = {}, onFavorite = {},
                    onDelete = {}, onMenu = {},
                    showFavorite = false,
                )
            }
        }
        compose.onNodeWithContentDescription("Избранное").assertDoesNotExist()
        compose.onNodeWithContentDescription("Поделиться").assertExists()
        compose.onNodeWithContentDescription("Обрезать").assertExists()
        compose.onNodeWithContentDescription("Удалить").assertExists()
        compose.onNodeWithContentDescription("Повернуть экран").assertExists()
    }
}
