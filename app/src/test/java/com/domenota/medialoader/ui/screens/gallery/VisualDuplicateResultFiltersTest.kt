package com.domenota.medialoader.ui.screens.gallery

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.library.VisualMatch
import org.junit.Assert.assertEquals
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
class VisualDuplicateResultFiltersTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun beforeScanNothingRendered() {
        compose.setContent { MaterialTheme {
            VisualDuplicateResultFilters(emptyMap(),null,{})
        } }
        compose.onNodeWithTag("visual-result-filters").assertDoesNotExist()
    }

    @Test fun onlyPopulatedGalleryKindsShown() {
        compose.setContent { MaterialTheme {
            VisualDuplicateResultFilters(mapOf(VisualMatch.SIMILAR_VIDEO to 3),null,{})
        } }
        compose.onNodeWithTag("visual-result-filter-ALL").assertExists()
        compose.onNodeWithTag("visual-result-filter-SIMILAR_VIDEO").assertExists()
        compose.onNodeWithTag("visual-result-filter-EXACT").assertDoesNotExist()
        compose.onNodeWithTag("visual-result-filter-SIMILAR_PHOTO").assertDoesNotExist()
        compose.onNodeWithText("Видео: 3").assertExists()
    }

    @Test fun hiddenOldGallerySelectionFallsBackToAll() {
        var selected by mutableStateOf<VisualMatch?>(VisualMatch.EXACT)
        var counts by mutableStateOf(mapOf(VisualMatch.SIMILAR_PHOTO to 1))
        compose.setContent { MaterialTheme {
            VisualDuplicateResultFilters(counts,selected,{
                selected=it
            })
        } }
        compose.onNodeWithTag("visual-result-filter-ALL").assertIsSelected()
        compose.onNodeWithTag("visual-result-filter-SIMILAR_PHOTO").performClick()
        compose.runOnIdle { assertEquals(VisualMatch.SIMILAR_PHOTO,selected) }
        compose.runOnIdle { counts=mapOf(VisualMatch.EXACT to 2,VisualMatch.SIMILAR_VIDEO to 3) }
        val all=compose.onNodeWithTag("visual-result-filter-ALL").fetchSemanticsNode().boundsInRoot
        val exact=compose.onNodeWithTag("visual-result-filter-EXACT").fetchSemanticsNode().boundsInRoot
        val video=compose.onNodeWithTag("visual-result-filter-SIMILAR_VIDEO").fetchSemanticsNode().boundsInRoot
        assertEquals(all.top,exact.top,1f)
        assertEquals(all.top,video.top,1f)
        assertEquals(all.width,video.width,1f)
        compose.onNodeWithText("Точные: 2").assertExists()
        compose.onNodeWithText("Видео: 3").assertExists()
    }
}
