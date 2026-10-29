package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.library.LibrarySource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryAlbumNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun albumHasOwnHeaderHidesDockAndRestoresItsSelection() {
        val restoration = StateRestorationTester(compose)
        val photo = DownloadEntity("a", "device", "Photo.jpg", MediaType.PHOTO, DownloadState.COMPLETED, "file:///a.jpg", null, 0)
        var detail = false
        restoration.setContent { MaterialTheme {
            GalleryModeScreen(listOf(photo), LibrarySource.APP, false, null,
                listOf(CatalogFolderEntity("f", "Отпуск", "отпуск", 0)), listOf(CatalogAssignmentEntity("a", "f", false)),
                { Text("Главная шапка") }, { _, _ -> }, {}, { _, _ -> }, { _, _ -> }, { "new" }, {}, {}, { _, _ -> }, {},
                searchVisible = false, onDetailChange = { detail = it })
        } }
        compose.onNodeWithText("Альбомы").performClick()
        compose.onNodeWithText("Отпуск").performClick()
        compose.onNodeWithText("Главная шапка").assertDoesNotExist()
        compose.onNodeWithText("Альбомы").assertDoesNotExist()
        compose.onNodeWithContentDescription("Photo.jpg").assertIsDisplayed()
        assertTrue(detail)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Отпуск").assertExists()
        compose.onNodeWithContentDescription("Photo.jpg").assertIsDisplayed()
        compose.onNodeWithContentDescription("Все альбомы").performClick()
        compose.onNodeWithText("Главная шапка").assertExists()
        compose.onNodeWithText("Альбомы").assertExists()
        assertFalse(detail)
    }
}
