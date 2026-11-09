package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ObjectManualTracePolicyTest {
    private val path = listOf(MaskPoint(.1f, .2f), MaskPoint(.3f, .4f))

    @Test fun emptyPathDoesNotCreateAnInpaintingMask() {
        assertNull(ObjectManualTracePolicy.toStroke(emptyList(), 1280, 720))
    }

    @Test fun manualObjectPathIsNarrowAndNeverAnEraser() {
        val stroke = requireNotNull(ObjectManualTracePolicy.toStroke(path, 1280, 720))
        assertEquals(path, stroke.points)
        assertEquals(7f / 720f, stroke.diameter, .00001f)
        assertFalse(stroke.erase)
    }

    @Test fun diameterIsIndependentOfUserBrushAndUsesWorkingImage() {
        val small = requireNotNull(ObjectManualTracePolicy.toStroke(path, 400, 400))
        val large = requireNotNull(ObjectManualTracePolicy.toStroke(path, 1280, 1280))
        assertEquals(7f, small.diameter * 400f, .01f)
        assertEquals(7f, large.diameter * 1280f, .01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroSizedImageCannotCreateStroke() {
        ObjectManualTracePolicy.toStroke(path, 0, 400)
    }
}
