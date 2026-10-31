package com.domenota.medialoader.data.editor

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoToneMathTest {
    @Test fun hueRoundtripKeepsChannelValues() {
        val colors = listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f), floatArrayOf(.45f, .45f, .45f),
            floatArrayOf(.1f, .7f, .2f))
        colors.forEach { rgb ->
            val hsl = PhotoToneMath.rgbToHsl(rgb[0], rgb[1], rgb[2])
            val out = PhotoToneMath.hslToRgb(hsl[0], hsl[1], hsl[2])
            assertEquals(rgb[0], out[0], .001f)
            assertEquals(rgb[1], out[1], .001f)
            assertEquals(rgb[2], out[2], .001f)
        }
    }

    @Test fun hueWrapsWithoutBandDiscontinuity() {
        assertEquals(10f, PhotoToneMath.circularDistance(355f, 5f), .001f)
        assertEquals(25f, PhotoToneMath.circularDistance(360f, 25f), .001f)
    }

    @Test fun shadowsRecoverDarkPixelsAndHighlightsRecoverWhiteDetail() {
        val dark = Color.rgb(35, 30, 25)
        val brighter = PhotoToneMath.tonePixel(dark, PhotoColorAdjustments(shadows = 75f))
        assertTrue(Color.red(brighter) > Color.red(dark))
        val bright = Color.rgb(230, 225, 215)
        val recovered = PhotoToneMath.tonePixel(bright, PhotoColorAdjustments(highlights = -75f))
        assertTrue(Color.red(recovered) < Color.red(bright))
    }

    @Test fun gammaAndBlackPointAreNonDestructiveAndPreserveAlpha() {
        val pixel = Color.argb(173, 110, 125, 140)
        val modified = PhotoToneMath.tonePixel(pixel,
            PhotoColorAdjustments(curve = 35f, blackPoint = 15f))
        assertEquals(173, Color.alpha(modified))
        assertTrue(modified != pixel)
        assertEquals(110, Color.red(pixel))
    }

    @Test fun hslAffectsTargetHueButNotOtherBands() {
        val red = Color.rgb(235, 40, 45)
        val blue = Color.rgb(30, 65, 240)
        val settings = PhotoColorAdjustments(hsl = mapOf(
            HslRange.RED to HslCorrection(hue = 75f)))
        val changedRed = PhotoToneMath.tonePixel(red, settings)
        val changedBlue = PhotoToneMath.tonePixel(blue, settings)
        assertTrue(changedRed != red)
        assertEquals(Color.blue(blue), Color.blue(changedBlue), 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidHslCorrectionCannotBeSaved() { HslCorrection(lightness = 180f) }
}
