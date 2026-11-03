package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.domenota.medialoader.data.editor.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoSourceTrimGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun twentyToFifteenAndBackBothHandlesAtMdpi() = checkBothEnds(1f)
    @Test fun twentyToFifteenAndBackBothHandlesAtXhdpi() = checkBothEnds(2f)

    @Test fun releasingScrubKeepsFinalReelPosition() {
        val project = VideoProjectDraft(listOf(VideoClipDraft("a",
            "file:///synthetic-not-decoded.mp4", 20_000L)))
        var position by mutableLongStateOf(5_000L)
        compose.setContent { MaterialTheme {
            VideoMultiClipTimeline(project, "a", position, false, true, false, false,
                {}, {}, null, {}, {}, { position = it }, onTrim = { _, _, _ -> },
                onMove = { _, _ -> }, onAdd = {}, onTogglePlayback = {})
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("video-reel").performTouchInput {
            down(center)
            moveBy(Offset(-80f, 0f), 80)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("video-reel").performTouchInput {
            // Final movement and release occur faster than the intermediate 50ms rate.
            moveBy(Offset(-40f, 0f), 1)
            up()
        }
        compose.waitForIdle()
        val released = position
        assertTrue("The reel must seek forward rather than snap back", released > 5_500L)
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertEquals(released, position)
    }

    private fun checkBothEnds(density: Float) {
        var project by mutableStateOf(VideoProjectDraft(listOf(
            VideoClipDraft("a", "file:///synthetic-not-decoded.mp4", 20_000L))))
        var position by mutableLongStateOf(0L)
        var endHandle = false
        compose.setContent {
            val defaultView = LocalViewConfiguration.current
            val precise = remember(defaultView) { object : ViewConfiguration by defaultView {
                override val touchSlop = 0f
            } }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalViewConfiguration provides precise) { MaterialTheme {
                VideoMultiClipTimeline(project, "a", position, false, true, false, false,
                    {}, {}, null, {}, {}, {},
                    onTrim = { id, from, to ->
                        project = project.trimClip(id, from, to)
                        position = if (endHandle) project.durationMs - 1 else 0L
                    }, onTrimSideChange = { endHandle = it },
                    trimMode = true, onMove = { _, _ -> }, onAdd = {}, onTogglePlayback = {})
            } }
        }
        fun keptWidth() = compose.onNodeWithTag("trim-kept-a", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().let { it.right.value - it.left.value }
        fun drag(side: String, seconds: Float) {
            compose.runOnIdle { position = if (side == "end") project.durationMs - 1 else 0L }
            compose.waitForIdle()
            val dpPerSecond = VideoClipReorderMath.pixelsPerSecondDp(2.5f, 20_000L)
            compose.onNodeWithTag("trim-$side-a", useUnmergedTree = true).performTouchInput {
                down(center)
                moveBy(Offset(seconds * dpPerSecond * density / 2f, 0f), 125)
            }
            // Allow source expansion and live bounds to recompose with the finger still down.
            compose.waitForIdle()
            compose.onNodeWithTag("trim-$side-a", useUnmergedTree = true).performTouchInput {
                moveBy(Offset(seconds * dpPerSecond * density / 2f, 0f), 125)
            }
            compose.waitForIdle()
            compose.onNodeWithTag("trim-$side-a", useUnmergedTree = true).performTouchInput { up() }
            compose.waitForIdle()
        }
        val full = keptWidth()
        drag("end", -5f)
        assertEquals(15_000L, project.durationMs)
        assertEquals(.75f, keptWidth() / full, .01f)
        drag("end", 5f)
        assertEquals(20_000L, project.durationMs)
        assertEquals(full, keptWidth(), .01f)
        drag("start", 5f)
        assertEquals(15_000L, project.durationMs)
        assertEquals(5_000L, project.clips.first().inMs)
        assertEquals(.75f, keptWidth() / full, .01f)
        drag("start", -5f)
        assertEquals(20_000L, project.durationMs)
        assertEquals(0L, project.clips.first().inMs)
    }
}
