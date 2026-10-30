package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoGraphicWatermarkTest {
    private val watermark = PhotoGraphicWatermark(
        uri = "content://com.android.providers.media.documents/document/image%3A17",
        relativeWidth = .2f, centerX = .9f, centerY = .8f, opacity = .6f)

    @Test fun clampsInsideTheFinalImageWithoutChangingProportions() {
        val b = photoWatermarkBounds(1000, 700, 300, 150, watermark)
        assertEquals(200f, b.width(), .01f)
        assertEquals(100f, b.height(), .01f)
        assertTrue(b.right <= 1000f)
        assertTrue(b.bottom <= 700f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun neverTreatsArbitraryFilePathsAsWatermarkSources() {
        PhotoGraphicWatermark(uri = "file:///data/local/tmp/untrusted.png")
    }

    @Test(expected = IllegalArgumentException::class)
    fun opacityMustRemainNormalized() {
        watermark.copy(opacity = 4f)
    }
}
