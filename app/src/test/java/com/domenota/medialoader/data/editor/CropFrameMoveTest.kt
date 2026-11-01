package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class CropFrameMoveTest {
    @Test fun wholeFrameMoveKeepsExactSizeAndRatio() {
        val crop = CropFractions(left = .2f, right = .35f, top = .1f, bottom = .4f)
        val next = VideoCropMath.moveFrame(crop, .12f, .2f)
        assertEquals(crop.keptWidth, next.keptWidth, .0001f)
        assertEquals(crop.keptHeight, next.keptHeight, .0001f)
        assertEquals(.32f, next.left, .0001f)
        assertEquals(.30f, next.top, .0001f)
        assertEquals(crop.keptWidth / crop.keptHeight,
            next.keptWidth / next.keptHeight, .0001f)
    }

    @Test fun draggingFramePastEdgesStopsAtImageBoundary() {
        val crop = CropFractions(.2f, .3f, .2f, .3f)
        val a = VideoCropMath.moveFrame(crop, 3f, -3f)
        assertEquals(0f, a.right, .0001f)
        assertEquals(0f, a.top, .0001f)
        assertEquals(.5f, a.keptWidth, .0001f)
        assertEquals(.5f, a.keptHeight, .0001f)
        val b = VideoCropMath.moveFrame(a, -3f, 3f)
        assertEquals(0f, b.left, .0001f)
        assertEquals(0f, b.bottom, .0001f)
    }

    @Test fun fullFrameCannotMove() {
        assertEquals(CropFractions(), VideoCropMath.moveFrame(CropFractions(), 1f, 1f))
    }
}
