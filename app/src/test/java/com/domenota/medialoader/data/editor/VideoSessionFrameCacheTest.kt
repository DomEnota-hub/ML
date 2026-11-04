package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoSessionFrameCacheTest {
    private fun key(ms: Long) = VideoSessionFrameCache.Key("source", "revision", ms, 100, 100)
    @Test fun lruIsBoundedButEvictionDoesNotRecycleDisplayedFrames() {
        val a = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val c = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        VideoSessionFrameCache(80_000).use { cache ->
            cache.put(key(0), a); cache.put(key(1000), b)
            assertSame(a, cache.get(key(0)))
            cache.put(key(2000), c)
            assertNull(cache.get(key(1000)))
            assertSame(a, cache.get(key(0)))
            assertTrue(cache.bytes() <= 80_000)
            assertFalse(b.isRecycled)
        }
        assertFalse(a.isRecycled)
    }
    @Test fun closingPreventsLateBackgroundResultsFromRepopulatingSession() {
        val frame = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val cache = VideoSessionFrameCache()
        cache.put(key(0), frame); cache.close(); cache.put(key(1000), frame)
        assertEquals(0, cache.bytes()); assertNull(cache.get(key(0))); assertNull(cache.get(key(1000)))
    }
}
