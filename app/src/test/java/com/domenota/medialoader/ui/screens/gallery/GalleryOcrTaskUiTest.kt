package com.domenota.medialoader.ui.screens.gallery

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class GalleryOcrTaskUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun indicatorShowsOnlyRealProgressAndOpensTaskWithoutTogglingIndex(){
        val controller=GalleryOcrController(compose.activity)
        var opened=0
        val wasEnabled=controller.enabled
        controller.progress=GalleryOcrProgress(GalleryOcrPhase.RUNNING,56,100)
        compose.setContent { MaterialTheme { Column {
            GalleryOcrIndicator(controller){opened++}
            GalleryOcrNotice(controller)
        } } }
        compose.onNodeWithText("56%").performClick()
        compose.runOnIdle{assertEquals(1,opened);assertEquals(wasEnabled,controller.enabled)
            controller.progress=GalleryOcrProgress(GalleryOcrPhase.WAITING_CATALOG)}
        compose.onNodeWithContentDescription("Индекс текста не готов").assertExists()
        compose.onNodeWithText("0%").assertDoesNotExist()
        compose.runOnIdle{controller.progress=GalleryOcrProgress(GalleryOcrPhase.COMPLETE,100,100)}
        compose.onNodeWithText("100%").assertDoesNotExist()
        compose.onNodeWithContentDescription("Индекс текста не готов").assertDoesNotExist()
    }
    @Test fun unknownAndPartialProgressNeverBecomeReady() {
        assertNull(GalleryOcrProgress(GalleryOcrPhase.WAITING_CATALOG).fraction)
        assertFalse(GalleryOcrProgress(GalleryOcrPhase.PARTIAL,2,2,failed=1).complete)
        assertTrue(GalleryOcrProgress(GalleryOcrPhase.COMPLETE,2,2).complete)
        assertFalse(GalleryOcrProgress(GalleryOcrPhase.COMPLETE,2,2,unverified=1).complete)
    }
}
