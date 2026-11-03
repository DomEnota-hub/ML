package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
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
class VideoAudioLaneGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun fullLengthAudioKeepsVideoWidthAndSmallTrimDoesNotReflowTheTrack() {
        val original = VideoAudioTrackDraft("audio", "file:///missing-test.mp4", 0L, 20_000L)
        var project by mutableStateOf(VideoProjectDraft(listOf(
            VideoClipDraft("clip", "file:///missing-test.mp4", 20_000L)), audioTracks = listOf(original)))
        var starts = 0; var ends = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { MaterialTheme {
            Box(Modifier.width(300.dp)) {
                VideoAudioLanes(project, "audio", rememberLazyListState(), listOf(750f), true,
                    {}, { next -> project = project.editAudioTrack(next.id) { next } },
                    { starts++ }, { ends++ })
            }
        } } }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("audio-lane-audio", true).getUnclippedBoundsInRoot()
        assertEquals("Audio must not be squeezed into viewport width", 750f, bounds.right.value - bounds.left.value, .1f)
        compose.onNodeWithTag("audio-start-audio", true).performTouchInput {
            down(center); moveBy(Offset(4f, 0f), 50)
        }
        compose.waitForIdle()
        assertTrue(project.audioTracks.single().inMs > 0)
        assertEquals(20_000L, project.audioTracks.single().endAtMs)
        assertEquals(1, starts)
        compose.onNodeWithTag("audio-start-audio", true).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, ends)
    }
}
