package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
class PreviewMusicActionTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun musicAndFrameActionsAreBoundedAndIndependentOfFollowingMedia(){
        var music=0;var frames=0
        compose.setContent{MaterialTheme{Column(Modifier.fillMaxWidth().testTag("action-parent")){
            FullSongTextLink({music++},true)
            FullSongTextLink({frames++},true,"Скачать кадр")
            Box(Modifier.fillMaxWidth().height(64.dp).testTag("following-media")){Text("Фото")}
        }}}
        val parent=compose.onNodeWithTag("action-parent").fetchSemanticsNode().boundsInRoot
        val song=compose.onNodeWithTag("full-song-action")
        val frame=compose.onNodeWithTag("save-frame-action")
        val songBounds=song.fetchSemanticsNode().boundsInRoot
        val frameBounds=frame.fetchSemanticsNode().boundsInRoot
        assertTrue(songBounds.width<parent.width)
        assertTrue(frameBounds.width<parent.width)
        assertEquals(parent.left,songBounds.left,.01f)
        assertTrue(songBounds.bottom<=frameBounds.top)
        assertTrue(frameBounds.bottom<=compose.onNodeWithTag("following-media").fetchSemanticsNode().boundsInRoot.top)
        song.performClick();frame.performClick()
        compose.runOnIdle{assertEquals(1,music);assertEquals(1,frames)}
    }
}
