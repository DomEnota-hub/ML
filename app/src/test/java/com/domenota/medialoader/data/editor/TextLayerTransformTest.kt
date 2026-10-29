package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class TextLayerTransformTest {
    @Test fun handleScalesAndRotatesAroundFixedCenter() {
        val layer = TextLayer(size = 0.06f, rotation = 10f, centerX = 0.3f)
        val result = TextLayerTransform.fromHandle(layer, 10f, 0f, 0f, 20f)
        assertEquals(0.12f, result.size, 0.0001f)
        assertEquals(100f, result.rotation, 0.001f)
        assertEquals(layer.centerX, result.centerX)
        assertEquals(layer.centerY, result.centerY)
    }
    @Test fun rotationCrossesBoundaryWithoutJump() {
        val layer = TextLayer(rotation = 170f)
        assertEquals(-100f, TextLayerTransform.fromHandle(layer, 1f, 0f, 0f, 1f).rotation, 0.001f)
    }
    @Test fun resizingRespectsLimitsAndPivotDoesNotCreateInvalidLayer() {
        val layer = TextLayer()
        assertEquals(0.25f, TextLayerTransform.fromHandle(layer, 1f, 0f, 100f, 0f).size, 0f)
        assertEquals(0.02f, TextLayerTransform.fromHandle(layer, 100f, 0f, 1f, 0f).size, 0f)
        assertEquals(layer, TextLayerTransform.fromHandle(layer, 0f, 0f, 10f, 0f))
        assertEquals(layer, TextLayerTransform.fromHandle(layer, 10f, 0f, 0f, 0f))
    }
    @Test fun crossingPivotKeepsLastValidSizeAndAngle() {
        val start = TextLayer()
        val previous = start.copy(size = 0.02f, rotation = 75f)
        assertEquals(previous, TextLayerTransform.fromHandle(start, 100f, 0f, 0f, 0f, previous))
    }
    @Test fun handleAtBottomRightDoesNotCollapseOntoPivot() {
        val handle = TextHandlePlacement.place(346f, 246f, 410f, 280f, 360f, 260f, 14f, 38f)
        assertTrue(kotlin.math.hypot(handle.first - 346f, handle.second - 246f) >= 37.9f)
        assertTrue(handle.first in 14f..346f && handle.second in 14f..246f)
    }
    @Test fun smallTextHandleLeavesRoomToGrabTextCenter() {
        val handle = TextHandlePlacement.place(180f, 130f, 190f, 140f, 360f, 260f, 14f, 38f)
        assertTrue(kotlin.math.hypot(handle.first - 180f, handle.second - 130f) >= 37.9f)
    }
}
