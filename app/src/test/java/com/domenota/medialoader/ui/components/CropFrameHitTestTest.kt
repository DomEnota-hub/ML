package com.domenota.medialoader.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.domenota.medialoader.data.editor.CropFractions
import com.domenota.medialoader.data.editor.VideoCropHandle
import org.junit.Assert.assertEquals
import org.junit.Test

class CropFrameHitTestTest {
    @Test fun closestCornerWinsWhenInvisibleTouchAreasOverlap() {
        val rect = Rect(20f, 10f, 120f, 100f)
        val crop = CropFractions()
        fun hit(x: Float, y: Float) = hitTestCropHandle(
            Offset(x, y), crop, rect, edgeHitRadius = 32f, cornerHitRadius = 76f)
        assertEquals(VideoCropHandle.BOTTOM_LEFT, hit(23f, 67f))
        assertEquals(VideoCropHandle.BOTTOM_RIGHT, hit(117f, 67f))
        assertEquals(VideoCropHandle.TOP_LEFT, hit(23f, 11f))
        assertEquals(VideoCropHandle.TOP_RIGHT, hit(117f, 11f))
    }

    @Test fun cropFrameHitTestKeepsEdgesAndIgnoresDistantTouches() {
        val rect = Rect(20f, 10f, 420f, 300f)
        val crop = CropFractions()
        fun hit(x: Float, y: Float) = hitTestCropHandle(
            Offset(x, y), crop, rect, edgeHitRadius = 32f, cornerHitRadius = 76f)
        assertEquals(VideoCropHandle.LEFT, hit(20f, 145f))
        assertEquals(VideoCropHandle.RIGHT, hit(420f, 145f))
        assertEquals(VideoCropHandle.TOP, hit(220f, 10f))
        assertEquals(VideoCropHandle.BOTTOM, hit(220f, 300f))
        assertEquals(null, hit(800f, 900f))
    }

    @Test fun videoEditorRetainsLegacyCornerPreference() {
        val rect = Rect(20f, 10f, 120f, 100f)
        val crop = CropFractions()
        val touch = Offset(23f, 67f) // falls in both old corner hit areas
        assertEquals(VideoCropHandle.TOP_LEFT, hitTestCropHandle(
            touch, crop, rect, edgeHitRadius = 36f, cornerHitRadius = 64f,
            preferNearest = false))
        assertEquals(VideoCropHandle.BOTTOM_LEFT, hitTestCropHandle(
            touch, crop, rect, edgeHitRadius = 32f, cornerHitRadius = 76f,
            preferNearest = true))
    }
    @Test fun videoCircleMovesFromInteriorButKeepsOuterRingForResize() {
        val rect = Rect(0f, 0f, 500f, 300f)
        val crop = CropFractions(left = .2f, right = .2f, top = .1f, bottom = .1f)
        org.junit.Assert.assertTrue(isInsideCircleCropMoveZone(
            Offset(250f, 150f), crop, rect, 22f))
        org.junit.Assert.assertFalse(isInsideCircleCropMoveZone(
            Offset(250f, 45f), crop, rect, 22f))
        org.junit.Assert.assertFalse(isInsideCircleCropMoveZone(
            Offset(100f, 30f), crop, rect, 22f))
        org.junit.Assert.assertFalse(isInsideCircleCropMoveZone(
            Offset(800f, 900f), crop, rect, 22f))
    }

    @Test fun smallVideoCircleStillHasDraggableCenterDespiteLargeCornerTargets() {
        val rect = Rect(0f, 0f, 120f, 120f)
        val crop = CropFractions(left = .4f, right = .4f, top = .4f, bottom = .4f)
        org.junit.Assert.assertTrue(isInsideCircleCropMoveZone(
            Offset(60f, 60f), crop, rect, 22f))
        org.junit.Assert.assertFalse(isInsideCircleCropMoveZone(
            Offset(71f, 60f), crop, rect, 22f))
    }

}
