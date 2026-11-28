package com.domenota.medialoader.ui.screens.music

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.library.DuplicateKind
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
class MusicDuplicateResultFiltersTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun beforeScanNothingRendered() {
        compose.setContent { MaterialTheme {
            MusicDuplicateResultFilters(emptyMap(),null,{})
        } }
        compose.onNodeWithTag("duplicate-result-filters").assertDoesNotExist()
        compose.onNodeWithTag("duplicate-result-filter-ALL").assertDoesNotExist()
    }

    @Test fun onlyPopulatedMusicKindsShown() {
        compose.setContent { MaterialTheme {
            MusicDuplicateResultFilters(mapOf(DuplicateKind.EXACT to 2),null,{})
        } }
        compose.onNodeWithTag("duplicate-result-filters").assertIsDisplayed()
        compose.onNodeWithTag("duplicate-result-filter-ALL").assertIsDisplayed()
        compose.onNodeWithTag("duplicate-result-filter-EXACT").assertIsDisplayed()
        compose.onNodeWithTag("duplicate-result-filter-ACOUSTIC").assertDoesNotExist()
        compose.onNodeWithTag("duplicate-result-filter-POSSIBLE").assertDoesNotExist()
        compose.onNodeWithText("Точные: 2").assertExists()
    }

    @Test fun staleMusicSelectionRendersAllAndCanSwitch() {
        var selected by mutableStateOf<DuplicateKind?>(DuplicateKind.ACOUSTIC)
        var counts by mutableStateOf(mapOf(DuplicateKind.EXACT to 1))
        compose.setContent { MaterialTheme {
            MusicDuplicateResultFilters(counts,selected,{selected=it})
        } }
        compose.onNodeWithTag("duplicate-result-filter-ALL").assertIsSelected()
        compose.onNodeWithTag("duplicate-result-filter-EXACT").performClick()
        compose.runOnIdle { assertEquals(DuplicateKind.EXACT,selected) }
        compose.runOnIdle { counts = mapOf(DuplicateKind.ACOUSTIC to 4, DuplicateKind.POSSIBLE to 1) }
        val all = compose.onNodeWithTag("duplicate-result-filter-ALL").fetchSemanticsNode().boundsInRoot
        val acoustic = compose.onNodeWithTag("duplicate-result-filter-ACOUSTIC").fetchSemanticsNode().boundsInRoot
        val version = compose.onNodeWithTag("duplicate-result-filter-POSSIBLE").fetchSemanticsNode().boundsInRoot
        assertEquals(all.top, acoustic.top, 1f)
        assertEquals(all.top, version.top, 1f)
        assertEquals(all.width, version.width, 1f)
        compose.onNodeWithText("Акустика: 4").assertExists()
        compose.onNodeWithText("Версии: 1").assertExists()
    }

    @Test fun fourCategoriesRemainEqualAndLargeTextKeepsLastChipCentered() {
        val fontScale=androidx.compose.runtime.mutableStateOf(1f)
        val counts=androidx.compose.runtime.mutableStateOf(DuplicateKind.entries.associateWith{1})
        compose.setContent {
            val density=androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,fontScale.value)
            ) {
                MaterialTheme {
                    MusicDuplicateResultFilters(counts.value,null,{})
                }
            }
        }
        fun bounds(tag:String)=compose.onNodeWithTag("duplicate-result-filter-$tag").fetchSemanticsNode().boundsInRoot
        val all=bounds("ALL")
        val exact=bounds("EXACT")
        val acoustic=bounds("ACOUSTIC")
        val version=bounds("POSSIBLE")
        assertEquals(all.width,exact.width,1f)
        assertEquals(all.width,acoustic.width,1f)
        assertEquals(all.width,version.width,1f)
        assertEquals(all.top,exact.top,1f)
        assertEquals(acoustic.top,version.top,1f)
        compose.runOnIdle{
            fontScale.value=1.5f
            counts.value=mapOf(DuplicateKind.ACOUSTIC to 1,DuplicateKind.POSSIBLE to 1)
        }
        val largeAll=bounds("ALL")
        val largeAcoustic=bounds("ACOUSTIC")
        val largeVersion=bounds("POSSIBLE")
        assertEquals(largeAll.width,largeVersion.width,1f)
        assertEquals((largeAll.left+largeAcoustic.right)/2f,largeVersion.center.x,1f)
        assertEquals(largeAll.top,largeAcoustic.top,1f)
        org.junit.Assert.assertTrue(largeVersion.top>largeAll.top)
        compose.onNodeWithText("Версии: 1").assertExists()
    }
}
