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
        val accumulated = geometryRulerDragValue(0f, -10f, 4f, -100f..100f)
        assertEquals(3, accumulated.roundToInt())
    }
    @Test fun fasterRulerIsSymmetricAndStillAccumulatesSmallMoves() {
        assertEquals(100f,geometryRulerDragValue(0f,-200f,2f,-100f..100f),.001f)
        assertEquals(-100f,geometryRulerDragValue(0f,200f,2f,-100f..100f),.001f)
        assertEquals(100f,geometryRulerDragValue(0f,-200f,2f,-100f..100f),.001f)
        assertEquals(-100f,geometryRulerDragValue(0f,200f,2f,-100f..100f),.001f)
        var raw=0f;repeat(4) { raw=geometryRulerDragValue(raw,-.5f,2f,-100f..100f) }
        assertEquals(1f,raw,.001f)
    }
}
