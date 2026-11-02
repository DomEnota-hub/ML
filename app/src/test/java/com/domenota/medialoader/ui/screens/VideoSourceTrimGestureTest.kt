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

    private fun checkBothEnds(density: Float) {
        var project by mutableStateOf(VideoProjectDraft(listOf(
            VideoClipDraft("a", "file:///synthetic-not-decoded.mp4", 20_000L))))
        compose.setContent {
            val defaultView = LocalViewConfiguration.current
            val precise = remember(defaultView) { object : ViewConfiguration by defaultView {
                override val touchSlop = 0f
            } }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalViewConfiguration provides precise) { MaterialTheme {
                VideoMultiClipTimeline(project, "a", 0L, false, true, false, false,
                    {}, {}, null, {}, {}, {},
                    onTrim = { id, from, to -> project = project.trimClip(id, from, to) },
                    trimMode = true, onMove = { _, _ -> }, onAdd = {}, onTogglePlayback = {})
            } }
        }
        fun keptWidth() = compose.onNodeWithTag("trim-kept-a", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().let { it.right.value - it.left.value }
        fun drag(side: String, seconds: Float) {
            val dpPerSecond = VideoClipReorderMath.pixelsPerSecondDp(2.5f, 20_000L)
            compose.onNodeWithTag("trim-$side-a", useUnmergedTree = true).performTouchInput {
                down(center)
                moveBy(Offset(seconds * dpPerSecond * density, 0f), 250)
                up()
            }
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
