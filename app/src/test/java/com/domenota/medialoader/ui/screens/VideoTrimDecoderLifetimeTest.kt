package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoTrimDecoderLifetimeTest {
    @Test fun oneSourcePerGestureAndCloseOnSourceSwitchOrCancellation() {
        var opened = 0; var closed = 0; var frames = 0
        val decoder = VideoTrimBoundaryPreview.Decoder(RuntimeEnvironment.getApplication()) {
            opened++
            object : VideoTrimBoundaryPreview.Source {
                override fun frame(sourceMs: Long): Bitmap? { frames++; return null }
                override fun close() { closed++ }
            }
        }
        decoder.decode(VideoTrimBoundaryPreview.Request("one", 100))
        decoder.decode(VideoTrimBoundaryPreview.Request("one", 200))
        assertEquals(1, opened); assertEquals(2, frames)
        decoder.decode(VideoTrimBoundaryPreview.Request("two", 300))
        assertEquals(2, opened); assertEquals(1, closed)
        decoder.close(); decoder.close()
        assertEquals(2, closed)
        assertNull(decoder.decode(VideoTrimBoundaryPreview.Request("three", 400)))
        assertEquals(2, opened)
    }
}
