package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoTimelineAnchorUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun leftCursorAlignsVideoAndAudioAndCanReachTheVeryEnd() {
        var at by mutableLongStateOf(0L)
        val clip = VideoClipDraft("clip", "file:///missing-timeline.mp4", 10_000L)
        val project = VideoProjectDraft(listOf(clip), audioTracks = listOf(
            VideoAudioTrackDraft("sound", clip.sourceUri, 0L, 10_000L)))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { MaterialTheme {
            Box(Modifier.width(300.dp)) { VideoMultiClipTimeline(project, "clip", at, false,
                true, false, false, {}, {}, null, {}, {}, { at = it }, { _, _, _ -> },
                onMove = { _, _ -> }, onAdd = {}, onTogglePlayback = {}, showFrameSteps = false) }
        } } }
        compose.waitForIdle()
        val video = compose.onNodeWithTag("video-clip-clip", true).getUnclippedBoundsInRoot()
        val audio = compose.onNodeWithTag("audio-lane-sound", true).getUnclippedBoundsInRoot()
        val cursor = compose.onNodeWithTag("video-playhead", true).getUnclippedBoundsInRoot()
        val audioCursor = compose.onNodeWithTag("audio-playhead-sound", true).getUnclippedBoundsInRoot()
        val anchor = (cursor.left.value + cursor.right.value) / 2
        assertEquals(32f, anchor, 1f)
        assertEquals(anchor, video.left.value, 1f)
        assertEquals(anchor, audio.left.value, 1f)
        assertEquals(anchor, (audioCursor.left.value + audioCursor.right.value) / 2, 1f)
        compose.runOnIdle { at = 9_999L }
        compose.waitForIdle()
        val end = compose.onNodeWithTag("video-clip-clip", true).getUnclippedBoundsInRoot()
        assertEquals("Trailing padding must allow the end to reach the left cursor", anchor, end.right.value, 2f)
    }
}
