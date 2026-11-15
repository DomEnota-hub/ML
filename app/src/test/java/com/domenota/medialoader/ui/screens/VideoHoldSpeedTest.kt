package com.domenota.medialoader.ui.screens

import org.junit.Assert.*
import org.junit.Test

class VideoHoldSpeedTest {
    @Test fun boostRestoresEveryOriginalSpeedAndOnlyAppliesTwice() {
        for (original in listOf(.75f, 1f, 1.25f, 1.5f, 2f)) {
            val state = VideoHoldSpeedSession()
            val calls = mutableListOf<Float>()
            assertTrue(state.begin(original, true) {
                assertTrue(state.active) // synchronous Media3 listener must suppress temporary preference
                calls += it
            })
            assertFalse(state.begin(2f, true, { calls += it }))
            state.end { assertFalse(state.active); calls += it }
            state.end({ calls += it })
            assertEquals(listOf(2f, original), calls)
        }
    }

    @Test fun pausedAndInvalidSpeedCannotStartOrRestore() {
        val state = VideoHoldSpeedSession()
        val calls = mutableListOf<Float>()
        assertFalse(state.begin(1f, false, { calls += it }))
        assertFalse(state.begin(Float.NaN, true, { calls += it }))
        assertFalse(state.begin(0f, true, { calls += it }))
        state.end({ calls += it })
        assertTrue(calls.isEmpty())
        assertFalse(state.active)
    }

    @Test fun cancellationThenNewGestureUsesNewBaseAndIsIndependentOfOldPlayer() {
        val first = VideoHoldSpeedSession()
        val second = VideoHoldSpeedSession()
        val calls = mutableListOf<Float>()
        assertTrue(first.begin(1.25f, true, { calls += it }))
        first.end({ calls += it })
        assertTrue(second.begin(1.5f, true, { calls += it }))
        first.end({ calls += it })
        assertTrue(second.active)
        second.end({ calls += it })
        assertEquals(listOf(2f, 1.25f, 2f, 1.5f), calls)
    }

    @Test fun failedSpeedCommandDoesNotLeaveTheSessionActive() {
        val state = VideoHoldSpeedSession()
        try {
            state.begin(1f, true) { throw IllegalStateException("released player") }
            fail("Expected command failure")
        } catch (_: IllegalStateException) {
            assertFalse(state.active)
        }
        assertTrue(state.begin(1.25f, true) {})
        try {
            state.end { throw IllegalStateException("released player") }
            fail("Expected command failure")
        } catch (_: IllegalStateException) {
            assertFalse(state.active)
        }
        assertTrue(state.begin(1f, true) {})
        state.end {}
    }

    @Test fun onlyExistingForwardZoneQualifiesInBothOrientations() {
        for (width in listOf(360, 800)) {
            assertFalse(VideoHoldSpeedPolicy.isRightZone(width * .2f, width))
            assertFalse(VideoHoldSpeedPolicy.isRightZone(width * .5f, width))
            assertFalse(VideoHoldSpeedPolicy.isRightZone(width * .58f, width))
            assertTrue(VideoHoldSpeedPolicy.isRightZone(width * .8f, width))
            assertFalse(VideoHoldSpeedPolicy.isRightZone(width + 1f, width))
        }
        assertFalse(VideoHoldSpeedPolicy.isRightZone(1f, 0))
        assertEquals(400L, VideoHoldSpeedPolicy.HOLD_MS)
    }
}
