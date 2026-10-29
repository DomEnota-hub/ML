package com.domenota.medialoader.data.preview

import android.graphics.Bitmap
import android.graphics.Color
import com.domenota.medialoader.data.preferences.VideoThumbnailMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PreferredVideoThumbnailTest {
    private fun bitmap(color: Int) = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    @Test fun sourceCoverWinsWithoutDecodingVideoAndFrameModeSkipsSource() = runBlocking {
        var frameReads = 0
        var sourceReads = 0
        val loadSource: suspend (String) -> Bitmap? = { sourceReads++; bitmap(Color.RED) }
        val loadFrame: suspend () -> Bitmap? = { frameReads++; bitmap(Color.BLUE) }
        val source = preferredVideoThumbnail(VideoThumbnailMode.SOURCE, "https://example.org/cover", loadSource, loadFrame)
        assertEquals(Color.RED, source!!.getPixel(0, 0))
        assertEquals(0, frameReads)
        val frame = preferredVideoThumbnail(VideoThumbnailMode.FRAME, "https://example.org/cover", loadSource, loadFrame)
        assertEquals(Color.BLUE, frame!!.getPixel(0, 0))
        assertEquals(1, sourceReads)
        assertEquals(1, frameReads)
    }
    @Test fun missingOrFailedSourceFallsBackToARealFrame() = runBlocking {
        for (url in listOf(null, "https://example.org/missing")) {
            val result = preferredVideoThumbnail(VideoThumbnailMode.SOURCE, url, { throw IOException("offline") }, { bitmap(Color.BLUE) })
            assertEquals(Color.BLUE, result!!.getPixel(0, 0))
        }
    }
    @Test fun cancellationDoesNotStartFrameDecoding() = runBlocking {
        var decoded = false
        try {
            preferredVideoThumbnail(VideoThumbnailMode.SOURCE, "https://example.org/c", { throw CancellationException() }, { decoded = true; bitmap(Color.BLUE) })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { assertFalse(decoded) }
    }
}
