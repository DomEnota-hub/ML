package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGesturePolicyTest {
    @Test
    fun `small or diagonal drags do not activate player gestures`() {
        assertEquals(VideoGestureMode.NONE,
            VideoGesturePolicy.intentionalMode(20f, 15f, 3f, 300f, 24f))
        assertEquals(VideoGestureMode.NONE,
            VideoGesturePolicy.intentionalMode(20f, 30f, 28f, 300f, 24f))
        assertEquals(VideoGestureMode.SEEK,
            VideoGesturePolicy.intentionalMode(20f, 40f, 8f, 300f, 24f))
        assertEquals(VideoGestureMode.VOLUME,
            VideoGesturePolicy.intentionalMode(280f, 5f, -40f, 300f, 24f))
    }

    @Test
    fun `one vertical gesture can traverse the entire range`() {
        assertEquals(1f, VideoGesturePolicy.guardedLevel(0f, -400f, 400f), 0.001f)
        assertEquals(0f, VideoGesturePolicy.guardedLevel(1f, 400f, 400f), 0.001f)
    }
    @Test fun verticalZonesAreNarrowEdgeStrips() {
        assertEquals(VideoGestureMode.NONE, VideoGesturePolicy.mode(60f, 0f, -100f, 300f))
        assertEquals(VideoGestureMode.NONE, VideoGesturePolicy.mode(240f, 0f, -100f, 300f))
        assertEquals(VideoGestureMode.NONE, VideoGesturePolicy.mode(45f, 0f, -100f, 300f))
        assertEquals(VideoGestureMode.NONE, VideoGesturePolicy.mode(255f, 0f, -100f, 300f))
        assertEquals(VideoGestureMode.BRIGHTNESS, VideoGesturePolicy.mode(44f, 0f, -100f, 300f))
        assertEquals(VideoGestureMode.VOLUME, VideoGesturePolicy.mode(256f, 0f, -100f, 300f))
    }
    @Test fun seekOnlyStartsInLowerHalfOfFullViewport() {
        assertEquals(VideoGestureMode.NONE, VideoGesturePolicy.intentionalMode(150f, 100f, 0f, 300f, 24f, startY = 199f, height = 400f))
        assertEquals(VideoGestureMode.SEEK, VideoGesturePolicy.intentionalMode(150f, 100f, 0f, 300f, 24f, startY = 200f, height = 400f))
        assertEquals(VideoGestureMode.NONE, VideoGesturePolicy.intentionalMode(960f, 100f, 0f, 1920f, 24f, startY = 539f, height = 1080f))
        assertEquals(VideoGestureMode.SEEK, VideoGesturePolicy.intentionalMode(960f, 100f, 0f, 1920f, 24f, startY = 540f, height = 1080f))
    }
    @Test fun horizontalMovementSeeksFromEitherSide() {
        assertEquals(VideoGestureMode.SEEK,
            VideoGesturePolicy.mode(30f, 100f, 20f, 300f))
        assertEquals(VideoGestureMode.SEEK,
            VideoGesturePolicy.mode(270f, -100f, 20f, 300f))
    }

    @Test fun verticalMovementUsesStartingSide() {
        assertEquals(VideoGestureMode.BRIGHTNESS,
            VideoGesturePolicy.mode(30f, 10f, -100f, 300f))
        assertEquals(VideoGestureMode.VOLUME,
            VideoGesturePolicy.mode(270f, 10f, -100f, 300f))
        assertEquals(VideoGestureMode.NONE,
            VideoGesturePolicy.mode(150f, 10f, -100f, 300f))
    }

    @Test fun verticalLevelIsClampedAndUpIncreasesIt() {
        assertEquals(0.75f, VideoGesturePolicy.level(0.5f, -100f, 400f), 0.001f)
        assertEquals(0f, VideoGesturePolicy.level(0.5f, 400f, 400f), 0.001f)
        assertEquals(1f, VideoGesturePolicy.level(0.5f, -400f, 400f), 0.001f)
    }
}
