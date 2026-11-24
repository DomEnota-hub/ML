package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
                DuplicateScanScopeScreen(listOf(alpha,beta),"Музыка",{},{selected=it})
            }
        }
        compose.onNodeWithTag("scope-select-folders").performClick()
        compose.onNodeWithText("Music").assertExists()
        compose.onNodeWithTag("scope-node-volume:external_primary|Music").performClick()
        compose.onNodeWithText("Выбрано: 2 папки",substring=true).assertExists()
        compose.onNodeWithTag("scope-start-selected").assertIsDisplayed()
            .assertIsEnabled().performClick()
        assertEquals(setOf(DuplicateFolderScope.key(alpha),DuplicateFolderScope.key(beta)),selected)
    }

    @Test fun filteredSelectAllDoesNotSelectHiddenSibling() {
        var selected: Set<String>? = null
        compose.setContent {
            MaterialTheme {
                DuplicateScanScopeScreen(listOf(alpha,beta),"Музыка",{},{selected=it})
            }
        }
        compose.onNodeWithTag("scope-select-folders").performClick()
        compose.onNodeWithTag("scope-search-folder").performTextInput("Alpha")
        compose.onNodeWithText("Beta").assertDoesNotExist()
        compose.onNodeWithText("Выбрать все").performClick()
        compose.onNodeWithTag("scope-start-selected").assertIsDisplayed()
            .assertIsEnabled().performClick()
        assertEquals(setOf(DuplicateFolderScope.key(alpha)),selected)
    }

    @Test fun footerStaysPinnedWhileScrollingManyNestedFolders() {
        val tracks = (1..130).map { audio("track$it", "Album$it") }
        var selected: Set<String>? = null
        compose.setContent {
            MaterialTheme {
                DuplicateScanScopeScreen(tracks, "Музыка", {}, { selected = it })
            }
        }
        compose.onNodeWithTag("scope-select-folders").performClick()
        compose.onNodeWithTag("scope-node-volume:external_primary|Music").performClick()
        compose.onNodeWithTag("scope-start-selected").assertIsDisplayed().assertIsEnabled()
        val before = compose.onNodeWithTag("scope-start-selected")
            .fetchSemanticsNode().boundsInRoot.top

        compose.onAllNodesWithContentDescription("Раскрыть папку").onFirst().performClick()
        compose.onNodeWithTag("scope-folder-list").performScrollToIndex(90)
        compose.waitForIdle()
        val after = compose.onNodeWithTag("scope-start-selected")
            .fetchSemanticsNode().boundsInRoot.top
        assertEquals("The CTA must not scroll with nested folders", before, after, 1f)
        compose.onNodeWithTag("scope-fixed-footer").assertIsDisplayed()
        compose.onNodeWithTag("scope-start-selected").assertIsDisplayed()
            .assertIsEnabled().performClick()
        assertEquals(130, selected?.size)
    }

    @Test
    @Config(sdk=[34], qualifiers="w393dp-h640dp-xhdpi")
    fun narrowShortWindowStillOffersSelectableFooter() {
        compose.setContent {
            MaterialTheme {
                DuplicateScanScopeScreen(listOf(alpha,beta), "Музыка", {}, {})
            }
        }
        compose.onNodeWithTag("scope-select-folders").performClick()
        compose.onNodeWithTag("scope-start-selected").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("scope-node-volume:external_primary|Music").performClick()
        compose.onNodeWithTag("scope-start-selected").assertIsDisplayed().assertIsEnabled()
    }
    @Test fun inAppPickerFooterRespectsActivitySafeDrawingBounds() {
        var selected: Set<String>? = null
        var navPx = 0f
        compose.setContent {
            MaterialTheme {
                navPx = with(LocalDensity.current) { 48.dp.toPx() }
                Scaffold(
                    modifier = Modifier.fillMaxSize().testTag("scope-host-activity"),
                    contentWindowInsets = WindowInsets(left = 0.dp, top = 24.dp, right = 0.dp, bottom = 48.dp),
                ) { inner ->
                    Box(Modifier.padding(inner).fillMaxSize().testTag("scope-host-safe-viewport")) {
                        DuplicateScanScopeScreen(
                            listOf(alpha, beta), "Музыка", {}, { selected = it })
                    }
                }
            }
        }
        compose.onNodeWithTag("scope-select-folders").performClick()
        compose.onNodeWithTag("scope-node-volume:external_primary|Music").performClick()
        val activity = compose.onNodeWithTag("scope-host-activity")
            .fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("scope-host-safe-viewport")
            .fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("scope-fixed-footer")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("scope-start-selected")
            .assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
        assertTrue("Host must reserve navigation bar space",
            viewport.bottom <= activity.bottom - navPx + 1f)
        assertTrue("Footer must remain inside Activity safe viewport",
            footer.bottom <= viewport.bottom + 1f)
        assertTrue("Button must be completely above Android navigation",
            button.bottom <= viewport.bottom + 1f && button.top >= footer.top - 1f)
        compose.onNodeWithTag("scope-start-selected").performClick()
        assertEquals(setOf(DuplicateFolderScope.key(alpha), DuplicateFolderScope.key(beta)),
            selected)
    }

    @Test fun unknownFolderRetainsReadableCountWithLongDisplayName() {
        val longFolder = "ОченьДлинноеНазваниеПапки".repeat(8)
        val track = audio("long", longFolder).copy(groupId = "external_primary:Music/$longFolder/")
        compose.setContent {
            MaterialTheme { DuplicateScanScopeScreen(listOf(track), "Музыка", {}, {}) }
        }
        compose.onNodeWithTag("scope-select-folders").performClick()
        compose.onNodeWithTag("scope-node-volume:external_primary|Music").performClick()
        compose.onNodeWithTag("scope-start-selected").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("scope-row-details-volume:external_primary|Music")
            .assertExists().assertTextContains("1 файл", substring = true)
    }

    @Test fun overviewCountsUniqueUriNotRepeatedCatalogAlias() {
        compose.setContent {
            MaterialTheme {
                DuplicateScanScopeScreen(listOf(alpha, alpha.copy(id="alias"), beta),
                    "Музыка", {}, {})
            }
        }
        compose.onNodeWithText("Все доступные файлы раздела: 2").assertExists()
        compose.onNodeWithTag("scope-select-folders").performClick()
        compose.onNodeWithTag("scope-node-volume:external_primary|Music").performClick()
        compose.onNodeWithText("Выбрано: 2 папки, 2 файла", substring = true).assertExists()
    }

    @Test fun unknownGalleryBucketKeepsLocationUnverified() {
        val photo = DownloadEntity("p","device","p.jpg",MediaType.PHOTO,
            DownloadState.COMPLETED,"content://media/images/p",100L,1L,
            groupId="DCIM/Camera",bulkMediaKey="bucket-one")
        compose.setContent {
            MaterialTheme { DuplicateScanScopeScreen(listOf(photo),"Галерея",{}, {}) }
        }
        compose.onNodeWithTag("scope-select-folders").performClick()
        compose.onNodeWithText("Расположение не определено").assertExists()
        compose.onNodeWithTag("scope-node-folder:bucket:bucket-one")
            .assertExists().performClick()
        compose.onNodeWithTag("scope-start-selected").assertIsEnabled()
    }

    @Test fun pickerBackReturnsToScopeOverviewWithoutClosingRoute() {
        var closed = 0
        compose.setContent {
            MaterialTheme {
                DuplicateScanScopeScreen(listOf(alpha, beta), "Музыка", { closed++ }, {})
            }
        }
        compose.onNodeWithTag("scope-select-folders").performClick()
        compose.onNodeWithTag("scope-search-folder").assertExists()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("scope-select-folders").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, closed) }
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

}
