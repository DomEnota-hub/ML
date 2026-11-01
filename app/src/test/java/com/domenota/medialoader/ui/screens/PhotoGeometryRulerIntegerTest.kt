package com.domenota.medialoader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class PhotoGeometryRulerIntegerTest {
    @Test fun smallGesturesAccumulateBeforeRoundingToWholeUnit() {
        var raw = 0f
        for (delta in listOf(-1f, -1f, -1f, -1f)) {
            raw = geometryRulerDragValue(raw, delta, pixelsPerUnit = 4f, range = -100f..100f)
        }
        assertEquals(1f, raw.roundToInt().toFloat(), .0001f)
    }

    @Test fun maximumIsReachableWithOneScreenLengthOfRulerMovement() {
        assertEquals(100f, geometryRulerDragValue(0f, -400f, 4f, -100f..100f), .0001f)
        assertEquals(-100f, geometryRulerDragValue(0f, 400f, 4f, -100f..100f), .0001f)
    }

    @Test fun draggingPastExtremesCannotExceedAllowedBounds() {
        assertEquals(100f, geometryRulerDragValue(99f, -1000f, 4f, -100f..100f), .0001f)
        assertEquals(-100f, geometryRulerDragValue(-99f, 1000f, 4f, -100f..100f), .0001f)
    }

    @Test fun straighteningUsesWholeDegreesToo() {
        val accumulated = geometryRulerDragValue(0f, -10f, 4f, -45f..45f)
        assertEquals(3, accumulated.roundToInt())
    }
}
