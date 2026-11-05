package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalTextOcrFallbackTest {
    @Test fun emptyOrUnreliableCandidateDoesNotBecomeFakeText() {
        val chosen = LocalTextOcr.bestAttempt(listOf(
            LocalTextOcr.OcrAttempt("", 0, "auto"),
            LocalTextOcr.OcrAttempt("xy", 8, "sparse"),
            LocalTextOcr.OcrAttempt("!", 99, "block")
        ))
        assertEquals("", chosen.text)
        assertEquals(0, chosen.confidence)
    }

    @Test fun selectsReliableCyrillicFallbackAfterEmptyPageDetection() {
        val chosen = LocalTextOcr.bestAttempt(listOf(
            LocalTextOcr.OcrAttempt("", 0, "auto"),
            LocalTextOcr.OcrAttempt("приветика", 79, "sparse"),
            LocalTextOcr.OcrAttempt("прив", 27, "block")
        ))
        assertEquals("приветика", chosen.text)
        assertEquals(79, chosen.confidence)
    }

    @Test fun splitLettersAndPunctuationDoNotCountAsRecognizedText() {
        val wrong = LocalTextOcr.OcrAttempt("= à \\n o = 4 \\\\n p .", 80, "auto")
        assertTrue("The suspicious OCR output must be rejected", !wrong.usable)
        assertEquals("", LocalTextOcr.bestAttempt(listOf(wrong)).text)
        assertTrue(LocalTextOcr.OcrAttempt("приветика", 58, "caption").usable)
    }

    @Test fun highContrastFallbackFlattensWhiteAndDarkOutlinedLetters() {
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        bitmap.setPixel(3, 3, Color.BLACK)
        bitmap.setPixel(4, 3, Color.rgb(50, 50, 50))
        bitmap.setPixel(0, 0, Color.TRANSPARENT)
        try {
            val contrasted = LocalTextOcr.enhanceTextForOcr(bitmap)
            try {
                var darkPixels = 0
                for (y in 0 until contrasted.height) for (x in 0 until contrasted.width) {
                    if (contrasted.getPixel(x, y) == Color.BLACK) darkPixels++
                }
                assertTrue("Dark letter strokes must survive enhancement", darkPixels > 0)
                assertEquals(Color.WHITE, contrasted.getPixel(0, 0))
                assertTrue(contrasted.width >= bitmap.width)
            } finally { contrasted.recycle() }
        } finally { bitmap.recycle() }
    }
}
