package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.DuplicateFolderScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], qualifiers="w393dp-h852dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DuplicateScanScopeDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun audio(id: String, folder: String) = DownloadEntity(
        id, "device", "$id.mp3", MediaType.AUDIO, DownloadState.COMPLETED,
        "content://media/audio/$id", 1000L, 1L, groupId="external_primary:Music/$folder/"
    )
    private val alpha = audio("a", "Alpha")
    private val beta = audio("b", "Beta")

    @Test fun selectingParentSelectsOnlyItsVerifiedChildFolders() {
        var selected: Set<String>? = null
        compose.setContent {
            MaterialTheme {
                DuplicateScanScopeDialog(listOf(alpha,beta),"Музыка",{},{selected=it})
            }
        }
        compose.onNodeWithTag("scope-select-folders").performClick()
        compose.onNodeWithText("Music").assertExists()
        compose.onNodeWithTag("scope-node-volume:external_primary|Music").performClick()
        compose.onNodeWithText("Выбрано: 2 папки",substring=true).assertExists()
        compose.onNodeWithTag("scope-start-selected").assertIsEnabled().performClick()
        assertEquals(setOf(DuplicateFolderScope.key(alpha),DuplicateFolderScope.key(beta)),selected)
    }

    @Test fun filteredSelectAllDoesNotSelectHiddenSibling() {
        var selected: Set<String>? = null
        compose.setContent {
            MaterialTheme {
                DuplicateScanScopeDialog(listOf(alpha,beta),"Музыка",{},{selected=it})
            }
        }
        compose.onNodeWithTag("scope-select-folders").performClick()
        compose.onNodeWithTag("scope-search-folder").performTextInput("Alpha")
        compose.onNodeWithText("Beta").assertDoesNotExist()
        compose.onNodeWithText("Выбрать все").performClick()
        compose.onNodeWithTag("scope-start-selected").assertIsEnabled().performClick()
        assertEquals(setOf(DuplicateFolderScope.key(alpha)),selected)
    }
}
