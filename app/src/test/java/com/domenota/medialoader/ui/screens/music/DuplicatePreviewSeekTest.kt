package com.domenota.medialoader.ui.screens.music

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class DuplicatePreviewSeekTest{
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun previewUsesRealDurationAndCommitsUserSeek(){
        var target=-1L
        compose.setContent{MaterialTheme{DuplicatePreviewSeek("one",15000,180000,true){target=it}}}
        compose.onNodeWithText("0:15").assertExists()
        compose.onNodeWithText("3:00").assertExists()
        compose.onNodeWithTag("duplicate-seek-slider-one").performSemanticsAction(SemanticsActions.SetProgress){it(90000f)}
        compose.runOnIdle{assertEquals(90000L,target)}
    }
    @Test fun unknownDurationIsNotPresentedAsASeekableTrack(){
        compose.setContent{MaterialTheme{DuplicatePreviewSeek("unknown",0,0,false){fail("Seek must be disabled")}}}
        compose.onNodeWithTag("duplicate-seek-slider-unknown").assertIsNotEnabled()
        compose.onNodeWithText("—:—").assertExists()
    }
}
