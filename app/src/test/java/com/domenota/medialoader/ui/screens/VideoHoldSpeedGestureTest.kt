package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
class VideoHoldSpeedGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val events = mutableListOf<String>()
    private val enabled = mutableStateOf(true)

    private fun setup() {
        compose.setContent {
            if (enabled.value) Box(Modifier.size(320.dp, 240.dp).testTag("video-canvas")
                .pointerInput(Unit) {
                    detectVideoCanvasTaps(
                        onTap = { events += "tap" },
                        onDoubleTap = { events += if (it.x > size.width * .58f) "forward" else "back" },
                        onHoldStart = { events += "boost"; true },
                        onHoldEnd = { events += "restore" },
                    )
                })
        }
        compose.waitForIdle()
    }

    @Test fun rightLongPressOnlyBoostsAndRestores() {
        setup()
        compose.onNodeWithTag("video-canvas").performTouchInput {
            longClick(Offset(width * .8f, height * .5f), durationMillis = 650L)
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(listOf("boost", "restore"), events) }
    }

    @Test fun doubleTapsRemainSeparateFromHold() {
        setup()
        val canvas = compose.onNodeWithTag("video-canvas")
        canvas.performTouchInput { doubleClick(Offset(width * .8f, height * .5f)) }
        compose.mainClock.advanceTimeBy(400)
        canvas.performTouchInput { doubleClick(Offset(width * .2f, height * .5f)) }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(listOf("forward", "back"), events) }
    }

    @Test fun leftHoldAndQuickRightDragNeverBoost() {
        setup()
        val canvas = compose.onNodeWithTag("video-canvas")
        canvas.performTouchInput { longClick(Offset(width * .2f, height * .5f), durationMillis = 650L) }
        compose.mainClock.advanceTimeBy(400)
        canvas.performTouchInput {
            down(Offset(width * .8f, height * .5f))
            moveBy(Offset(0f, -70f))
            advanceEventTime(500)
            up()
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertFalse(events.contains("boost")); assertFalse(events.contains("restore")) }
    }

    @Test fun secondFingerBeforeTimeoutCancelsHold() {
        setup()
        compose.onNodeWithTag("video-canvas").performTouchInput {
            down(0, Offset(width * .8f, height * .5f))
            down(1, Offset(width * .3f, height * .5f))
            advanceEventTime(600)
            up(1)
            up(0)
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertFalse(events.contains("boost")); assertFalse(events.contains("restore")) }
    }
}
