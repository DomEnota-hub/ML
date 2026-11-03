package com.domenota.medialoader.ui.screens
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class PhotoPreviewCacheTest {
    @Test fun cacheIsBoundedAndEvictionDoesNotRecycleDisplayedPixels() {
        val cache=PhotoPreviewCache(32)
        val first=DecodedImage(Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888),2,2)
        cache.put("1",first)
        cache.put("2",DecodedImage(Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888),2,2))
        cache.put("3",DecodedImage(Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888),2,2))
        assertNull(cache.get("1"));assertNotNull(cache.get("3"));assertFalse(first.bitmap.isRecycled)
        cache.clear();assertNull(cache.get("3"))
    }
}
