package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import androidx.media3.common.util.Size
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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoMutablePreviewOverlayTest {
    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also {
        b.getPixels(it, 0, b.width, 0, 0, b.width, b.height)
    }
    @Test fun pausedStrengthAndReselectionRepaintWithoutChangingTime() {
        val overlay = VideoSpecialFxOverlay(VideoSpecialFxDraft(VideoSpecialFxType.VIGNETTE, .5f), 0)
        overlay.configure(Size(160, 90))
        val half = pixels(overlay.getBitmap(250_000))
        overlay.update(VideoSpecialFxDraft(VideoSpecialFxType.VIGNETTE, 1f), 0)
        val full = pixels(overlay.getBitmap(250_000))
        assertFalse(half.contentEquals(full))
        overlay.update(VideoSpecialFxDraft(VideoSpecialFxType.VIGNETTE, 0f), 0)
        assertTrue(pixels(overlay.getBitmap(250_000)).all { it ushr 24 == 0 })
        overlay.update(VideoSpecialFxDraft(VideoSpecialFxType.VIGNETTE, .5f), 0)
        assertArrayEquals(half, pixels(overlay.getBitmap(250_000)))
        overlay.getBitmap(250_000).recycle()
    }
    @Test fun sameTextIdUpdatesOpacityAtSamePausedTime() {
        val layer = TextLayer(text = "A", size = .25f, outline = 0f)
        val overlay = VideoTextOverlay(RuntimeEnvironment.getApplication(), listOf(layer))
        overlay.configure(Size(160, 90))
        assertTrue(pixels(overlay.getBitmap(250_000)).any { it ushr 24 > 0 })
        overlay.update(listOf(layer.copy(opacity = 0f)), emptyList(), 0)
        assertTrue(pixels(overlay.getBitmap(250_000)).all { it ushr 24 == 0 })
        overlay.update(listOf(layer), emptyList(), 0)
        assertTrue(pixels(overlay.getBitmap(250_000)).any { it ushr 24 > 0 })
        overlay.getBitmap(250_000).recycle()
    }
}
