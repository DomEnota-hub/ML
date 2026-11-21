package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.catalog.visibility.SelectiveArchiveQaEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArchiveOverviewScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun ordinaryWholeFolderRouteWorksAndNoQaActionExistsWithoutCallback() {
        var opened=0
        var closed=0
        compose.setContent { MaterialTheme {
            ArchiveOverviewScreen(2,{ closed++ },{ opened++ })
        } }
        compose.onNodeWithTag("archive-overview").assertExists()
        compose.onNodeWithText("Архив").assertExists()
        compose.onNodeWithTag("archive-qa-consent").assertIsNotEnabled()
        compose.onNodeWithTag("archive-open-folders").performClick()
        assertEquals(1,opened)
        compose.onNodeWithContentDescription("Вернуться из Архива").performClick()
        assertEquals(1,closed)
    }

    @Test fun testCopiesMustBeOptedInBeforePreflightAndLargeBatchCannotStart() {
        val selected=mutableListOf<String>()
        compose.setContent { MaterialTheme {
            ArchiveOverviewScreen(0,{}, {},
                candidates=listOf(
                    ArchiveQaFolder("six","Тестовые шесть",6),
                    ArchiveQaFolder("thirteen","Слишком много",13),
                ),
                onPreview={selected.add(it)},
                onRefresh={}
            )
        } }
        compose.onNodeWithTag("archive-qa-preview-six").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("archive-qa-consent").performClick()
        compose.onNodeWithTag("archive-qa-preview-six").assertIsEnabled().performClick()
        compose.onNodeWithTag("archive-qa-preview-thirteen")
            .performScrollTo().assertIsNotEnabled()
        assertEquals(listOf("six"),selected)
    }

    @Test fun restoreAvailableWithoutEnablingFurtherHidesAndNeedsReviewRemainsBlocked() {
        val restored=mutableListOf<String>()
        compose.setContent { MaterialTheme {
            ArchiveOverviewScreen(0,{}, {},
                recovery=listOf(
                    SelectiveArchiveQaEntry("hidden",6,6,false,false),
                    SelectiveArchiveQaEntry("review",6,2,true,false),
                ),
                onRestore={restored.add(it)},onRefresh={}
            )
        } }
        compose.onNodeWithTag("archive-qa-consent").assertIsOff()
        compose.onNodeWithTag("archive-qa-restore-hidden")
            .performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("archive-qa-restore-review")
            .performScrollTo().assertIsNotEnabled()
        assertEquals(listOf("hidden"),restored)
    }
}
