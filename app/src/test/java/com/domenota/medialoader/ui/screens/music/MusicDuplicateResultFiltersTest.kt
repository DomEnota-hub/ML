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
        compose.onNodeWithText("Точные · 2").assertExists()
    }

    @Test fun staleMusicSelectionRendersAllAndCanSwitch() {
        var selected by mutableStateOf<DuplicateKind?>(DuplicateKind.ACOUSTIC)
        compose.setContent { MaterialTheme {
            MusicDuplicateResultFilters(mapOf(DuplicateKind.EXACT to 1),selected,{selected=it})
        } }
        compose.onNodeWithTag("duplicate-result-filter-ALL").assertIsSelected()
        compose.onNodeWithTag("duplicate-result-filter-EXACT").performClick()
        compose.runOnIdle { assertEquals(DuplicateKind.EXACT,selected) }
    }
}
