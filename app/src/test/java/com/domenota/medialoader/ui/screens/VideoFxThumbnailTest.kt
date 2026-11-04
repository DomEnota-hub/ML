package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import com.domenota.medialoader.data.editor.VideoSpecialFxType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoFxThumbnailTest {
    @Test fun everySpecialEffectHasDistinctRealImagePreview() {
        val source = Bitmap.createBitmap(144, 94, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(110, 80, 55))
        val fingerprints = VideoSpecialFxType.entries.map { type ->
            val frame = VideoFxThumbnail.render(source, type)
            assertEquals(144, frame.width)
            assertEquals(94, frame.height)
            val pixels = IntArray(frame.width * frame.height)
            frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            val signature = pixels.fold(0L) { hash, pixel ->
                hash * 31L + pixel.toLong()
            }
            frame.recycle()
            signature
        }
        assertEquals("Filter cards must render distinct images, not one icon",
            VideoSpecialFxType.entries.size, fingerprints.distinct().size)
        source.recycle()
    }
}
