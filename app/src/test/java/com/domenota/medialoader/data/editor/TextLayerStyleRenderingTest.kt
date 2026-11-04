package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextLayerStyleRenderingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun coverage(layer: TextLayer): Int {
        val b = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        return try {
            TextLayerRenderer.draw(context, Canvas(b), 300, 200, listOf(layer))
            val pixels = IntArray(300 * 200); b.getPixels(pixels, 0, 300, 0, 0, 300, 200)
            pixels.count { (it ushr 24) > 0 }
        } finally { b.recycle() }
    }
    @Test fun shadowAndBoldChangeRealPixelsAndZeroOpacityHidesBoth() {
        val regular = TextLayer(text = "Text", size = .2f, outline = 0f)
        val normal = coverage(regular)
        assertTrue(normal > 0)
        assertTrue(coverage(regular.copy(shadow = true)) > normal)
        assertTrue(coverage(regular.copy(bold = true)) > normal)
        assertEquals(0, coverage(regular.copy(bold = true, shadow = true, opacity = 0f)))
    }
    @Test fun multilineLeftCenterRightUseIndependentLayerAlignment() {
        val layer = TextLayer(text = "MMMM\nM", size = .2f, outline = 0f)
        val starts = TextLayerAlignment.entries.map {
            TextLayerRenderer.layout(context, 300, 200, layer.copy(alignment = it)).getLineLeft(1)
        }
        assertTrue(starts[0] < starts[1]); assertTrue(starts[1] < starts[2])
        val timed = layer.copy(shadow = true, bold = true, alignment = TextLayerAlignment.RIGHT,
            startMs = 500, endMs = 1000)
        assertFalse(timed.visibleAt(499)); assertTrue(timed.visibleAt(500)); assertFalse(timed.visibleAt(1000))
        assertEquals(TextLayerAlignment.RIGHT, timed.within(800)!!.alignment)
    }
}
