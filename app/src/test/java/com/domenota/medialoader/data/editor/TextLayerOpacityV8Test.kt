package com.domenota.medialoader.data.editor

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextLayerOpacityV8Test {
    @Test fun independentOpacityForEightTextLayersAndExportPaint() {
        val layers = (0 until 8).map { index ->
            TextLayer(text = "Текст ${index + 1}", opacity = index / 7f)
        }
        assertEquals(8, layers.map { it.id }.distinct().size)
        assertEquals(0f, layers.first().opacity, 0f)
        assertEquals(1f, layers.last().opacity, 0f)
        val sourceAlpha = Color.argb(128, 12, 34, 56)
        assertEquals(0, TextLayerRenderer.opacityAlpha(sourceAlpha, layers.first().opacity))
        assertEquals(128, TextLayerRenderer.opacityAlpha(sourceAlpha, layers.last().opacity))
        assertEquals(64, TextLayerRenderer.opacityAlpha(sourceAlpha, .5f))
    }

    @Test fun opacityMustBeFiniteAndStayWithinPercentRange() {
        for (invalid in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            try {
                TextLayer(opacity = invalid)
                fail("Opacity outside 0..1 must not be accepted")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }
}
