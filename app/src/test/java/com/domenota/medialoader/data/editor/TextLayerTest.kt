package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class TextLayerTest {
    @Test fun visibilityUsesClipRelativeTimeAndExclusiveEnd() {
        val layer = TextLayer(text = "Привет!", startMs = 3000, endMs = 8000)
        assertFalse(layer.visibleAt(2999))
        assertTrue(layer.visibleAt(3000))
        assertTrue(layer.visibleAt(7999))
        assertFalse(layer.visibleAt(8000))
    }
    @Test fun shorteningClipClampsEndAndDropsLayersOutsideOutput() {
        val layer = TextLayer(startMs = 3000, endMs = 8000)
        assertEquals(6000L, layer.within(6000)!!.endMs)
        assertNull(layer.within(3000))
        assertNull(layer.within(0))
    }
    @Test fun blankTextDoesNotShowAndSourceEditDefaultsKeepExistingExportsUnchanged() {
        assertFalse(TextLayer(text = "  ").visibleAt(0))
        assertTrue(PhotoEditSpec().textLayers.isEmpty())
        assertTrue(VideoEditSpec().textLayers.isEmpty())
        val layer = TextLayer()
        assertEquals(listOf(layer), VideoEditSpec(startMs = 15_000, textLayers = listOf(layer)).textLayers)
    }
    @Test fun invalidCoordinatesAndIntervalsAreRejected() {
        listOf<() -> Unit>(
            { TextLayer(centerX = Float.NaN) }, { TextLayer(size = Float.POSITIVE_INFINITY) },
            { TextLayer(startMs = 50, endMs = 50) }, { TextLayer(text = "x".repeat(501)) },
            { PhotoEditSpec(textLayers = List(9) { TextLayer() }) },
        ).forEach { invalid ->
            try { invalid(); fail("Invalid layer accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
