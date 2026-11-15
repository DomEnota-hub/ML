package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
class CompactAudioSeekTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun tapDragAndAccessibilityCommitOnceWithoutLargeSlider(){
        val commits=mutableListOf<Float>()
        compose.setContent{MaterialTheme{Box(Modifier.width(300.dp)){
            CompactAudioSeek(.1f,true){commits+=it;true}
        }}}
        val seek=compose.onNodeWithTag("collection-mini-seek")
        val bounds=seek.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width>bounds.height*10f)
        seek.performTouchInput{click(Offset(width*.25f,height*.5f))}
        compose.runOnIdle{assertEquals(1,commits.size);assertEquals(.25f,commits.last(),.03f)}
        seek.performTouchInput{swipe(Offset(width*.2f,height*.5f),Offset(width*.8f,height*.5f),durationMillis=350)}
        compose.runOnIdle{assertEquals(2,commits.size);assertEquals(.8f,commits.last(),.06f)}
        seek.performSemanticsAction(SemanticsActions.SetProgress){it(.6f)}
        compose.runOnIdle{assertEquals(3,commits.size);assertEquals(.6f,commits.last(),.001f)}
    }

    @Test fun unavailableSeekCannotCommit(){
        val enabled=mutableStateOf(true);var calls=0
        compose.setContent{MaterialTheme{CompactAudioSeek(.2f,enabled.value){calls++;true}}}
        compose.runOnIdle{enabled.value=false}
        val seek=compose.onNodeWithTag("collection-mini-seek")
        seek.assertIsNotEnabled().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))
        seek.performTouchInput{click(center)}
        compose.runOnIdle{assertEquals(0,calls)}
    }
}
