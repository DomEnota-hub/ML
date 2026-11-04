package com.domenota.medialoader.data.preview

import android.graphics.Bitmap
import android.graphics.Color
import com.domenota.medialoader.data.preferences.VideoThumbnailMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    @Test fun savedFrameIsPublishedWhileSourceCoverIsStillLoading() = runBlocking {
        val sourceStarted = CompletableDeferred<Unit>()
        val finishSource = CompletableDeferred<Bitmap?>()
        val fallback = CompletableDeferred<Bitmap>()
        val frame = bitmap(Color.BLUE)
        val cover = bitmap(Color.RED)
        val result = async {
            preferredVideoThumbnail(VideoThumbnailMode.SOURCE, "https://example.org/slow",
                loadSource = { sourceStarted.complete(Unit); finishSource.await() },
                loadFrame = { frame }, publishFallback = { fallback.complete(it) })
        }
        try {
            withTimeout(1_000) {
                sourceStarted.await()
                assertSame(frame, fallback.await())
            }
            assertFalse(result.isCompleted)
            finishSource.complete(cover)
            assertSame(cover, result.await())
        } finally { result.cancel() }
    }

    @Test fun cachedSourceSkipsNetworkAndLocalExtraction() = runBlocking {
        val cover = bitmap(Color.RED)
        var networkReads = 0
        var frameReads = 0
        val result = preferredVideoThumbnail(VideoThumbnailMode.SOURCE, "https://example.org/cached",
            loadSource = { networkReads++; cover },
            loadFrame = { frameReads++; bitmap(Color.BLUE) },
            loadCachedSource = { cover }, publishFallback = { fail("No fallback needed") })
        assertSame(cover, result)
        assertEquals(0, networkReads)
        assertEquals(0, frameReads)
    }

    @Test fun unavailableSourceKeepsPublishedFrameWithoutDecodingItAgain() = runBlocking {
        var reads = 0
        val frame = bitmap(Color.BLUE)
        var shown: Bitmap? = null
        val result = preferredVideoThumbnail(VideoThumbnailMode.SOURCE, "https://example.org/expired",
            loadSource = { null }, loadFrame = { reads++; frame },
            publishFallback = { shown = it })
        assertSame(frame, shown)
        assertSame(frame, result)
        assertEquals(1, reads)
    }

}
