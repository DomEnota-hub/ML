package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoCropMathTest {
    @Test
    fun squarePreset_centerCropsWideVideo() {
        val crop = VideoCropMath.centerCrop(1920, 1080, CropAspectPreset.RATIO_1_1)

        assertEquals(0.21875f, crop.left, 0.0001f)
        assertEquals(0.21875f, crop.right, 0.0001f)
        assertEquals(0f, crop.top, 0.0001f)
        assertEquals(0f, crop.bottom, 0.0001f)
    }

    @Test
    fun originalPreset_keepsWholeFrame() {
        assertEquals(
            CropFractions(),
            VideoCropMath.centerCrop(1080, 1920, CropAspectPreset.ORIGINAL),
        )
    }

    @Test
    fun fixedRatio_dragPreservesRequestedAspect() {
        val width = 1920
        val height = 1080
        val initial = VideoCropMath.centerCrop(width, height, CropAspectPreset.RATIO_1_1)
        val changed = VideoCropMath.changeEdge(
            current = initial,
            edge = VideoCropEdge.LEFT,
            requestedCrop = initial.left + 0.08f,
            sourceWidth = width,
            sourceHeight = height,
            preset = CropAspectPreset.RATIO_1_1,
        )

        val outputRatio =
            (width * changed.keptWidth) / (height * changed.keptHeight)
        assertEquals(1f, outputRatio, 0.001f)
    }

    @Test
    fun freeCrop_neverCollapsesFrame() {
        val crop = VideoCropMath.changeEdge(
            current = CropFractions(right = 0.4f),
            edge = VideoCropEdge.LEFT,
            requestedCrop = 0.9f,
            sourceWidth = 1000,
            sourceHeight = 1000,
            preset = CropAspectPreset.FREE,
        )

        assertEquals(0.05f, crop.keptWidth, 0.0001f)
    }
    @Test
    fun freeCornerDrag_movesBothEdgesSmoothly() {
        val changed = VideoCropMath.changeHandle(
            current = CropFractions(),
            handle = VideoCropHandle.TOP_LEFT,
            deltaXFraction = 0.1f,
            deltaYFraction = 0.2f,
            sourceWidth = 1000,
            sourceHeight = 1000,
            preset = CropAspectPreset.FREE,
        )

        assertEquals(0.1f, changed.left, 0.0001f)
        assertEquals(0.2f, changed.top, 0.0001f)
        assertEquals(0f, changed.right, 0.0001f)
        assertEquals(0f, changed.bottom, 0.0001f)
    }

    @Test
    fun fixedRatio_cornerDrag_preservesAspectAndOppositeCorner() {
        val width = 1920
        val height = 1080
        val initial = VideoCropMath.centerCrop(width, height, CropAspectPreset.RATIO_1_1)
        val changed = VideoCropMath.changeHandle(
            current = initial,
            handle = VideoCropHandle.TOP_LEFT,
            deltaXFraction = 0.08f,
            deltaYFraction = 0.04f,
            sourceWidth = width,
            sourceHeight = height,
            preset = CropAspectPreset.RATIO_1_1,
        )

        val outputRatio = (width * changed.keptWidth) / (height * changed.keptHeight)
        assertEquals(1f, outputRatio, 0.001f)
        assertEquals(initial.right, changed.right, 0.0001f)
        assertEquals(initial.bottom, changed.bottom, 0.0001f)
    }
}
