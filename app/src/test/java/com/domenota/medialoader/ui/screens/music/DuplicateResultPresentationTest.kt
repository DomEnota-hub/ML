package com.domenota.medialoader.ui.screens.music

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.library.DuplicateSavedSummary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DuplicateResultPresentationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun historicalCardOpensSavedReportWithoutStartingScan() {
        val summary=DuplicateSavedSummary(1700000000000L,621,2600,1875,14320000000L,1L)
        var opened=0
        compose.setContent { MaterialTheme { DuplicateSavedSummaryPanel(summary,{opened++}) } }
        compose.onNodeWithTag("duplicate-saved-summary").assertExists()
        compose.onNodeWithText("Сохранённый результат").assertExists()
        compose.onNodeWithText("621").assertExists()
        compose.onNodeWithText("2600").assertExists()
        compose.onNodeWithTag("duplicate-open-saved").performClick()
        compose.runOnIdle { assertEquals(1,opened) }
    }

    @Test fun restoreShowsOnlyTrueCountersAndNoManualProgressSlider() {
        compose.setContent { MaterialTheme {
            DuplicateRestorePanel(144,2600,true,{})
            DuplicateGroupSkeletons()
        } }
        compose.onNodeWithText("144 из 2600").assertExists()
        compose.onNodeWithText("6%").assertExists()
        compose.onNodeWithText("Без повторного анализа звука").assertExists()
        compose.onNodeWithTag("duplicate-restore-progress").assertExists()
        compose.onNodeWithTag("duplicate-group-skeletons").assertExists()
    }
}
