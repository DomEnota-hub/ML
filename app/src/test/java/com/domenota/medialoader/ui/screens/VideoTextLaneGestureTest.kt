package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class VideoTextLaneGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun existingHandleUsesNewDurationAfterChangingClipSpeed() {
        var project by mutableStateOf(VideoProjectDraft(listOf(VideoClipDraft("clip", "file:///clip.mp4", 5000))))
        var text by mutableStateOf(TextLayer(id = "layer", text = "Text", endMs = 3000))
        compose.setContent { MaterialTheme { Box(Modifier.width(600.dp)) {
            VideoTextLanes(project, listOf(text), text.id, 0, rememberLazyListState(),
                listOf(project.durationMs / 20f), true, {}, { text = it }, {}, {})
        } } }
        // Start the suspending pointer handler before changing the timeline geometry.
        compose.onNodeWithTag("text-lane-layer-END").performTouchInput { click() }
        compose.runOnIdle { project = project.copy(clips = listOf(project.clips.single().copy(speed = .5f))) }
        compose.onNodeWithTag("text-lane-layer-END").performTouchInput {
            down(center); moveBy(Offset(250f, 0f)); up()
        }
        compose.runOnIdle {
            assertTrue("Old 5s bound must not constrain the extended 10s timeline: ${text.endMs}", text.endMs!! > 7000)
            assertTrue(text.endMs!! <= project.durationMs)
        }
    }
}
