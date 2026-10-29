package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class InpaintingContractsTest {
    @Test fun normalizedCoordinatesAndValidMask() {
        val stroke = MaskStroke(
            points = listOf(MaskPoint(0.05f, 0.95f), MaskPoint(0.5f, 0.5f)),
            diameter = 0.03f,
        )
        assertEquals(2, stroke.points.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutsideImage() { MaskPoint(-0.1f, 0.5f) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyMask() { MaskStroke(emptyList(), 0.05f) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroBrushSize() { MaskStroke(listOf(MaskPoint(0.5f, 0.5f)), 0f) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsExcessivelyLargeWorkingImage() { InpaintingSettings(8192) }
}
