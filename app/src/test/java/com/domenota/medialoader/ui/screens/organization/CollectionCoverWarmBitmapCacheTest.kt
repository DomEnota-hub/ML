package com.domenota.medialoader.ui.screens.organization

import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class CollectionCoverWarmBitmapCacheTest {
    @After fun clean() = CollectionCoverWarmBitmapCache.clear()

    @Test fun previouslyDisplayedCoverStartsWarmAfterLazyRowRecreation() {
        val original=Bitmap.createBitmap(512,384,Bitmap.Config.ARGB_8888)
        CollectionCoverWarmBitmapCache.remember("collection-a-public-identity-generation1",original)
        val warm=CollectionCoverWarmBitmapCache.peek("collection-a-public-identity-generation1")
        assertNotNull(warm)
        assertEquals(256,warm!!.width)
        assertEquals(192,warm.height)
        assertNull(CollectionCoverWarmBitmapCache.peek("collection-b-public-identity-generation1"))
        original.recycle()
    }
    @Test fun changedIdentityCannotDisplayThePreviousCover() {
        val original=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
        CollectionCoverWarmBitmapCache.remember("photo-source-key:rev1",original)
        assertNotNull(CollectionCoverWarmBitmapCache.peek("photo-source-key:rev1"))
        assertNull(CollectionCoverWarmBitmapCache.peek("photo-source-key:rev2"))
        CollectionCoverWarmBitmapCache.forget("photo-source-key:rev1")
        assertNull(CollectionCoverWarmBitmapCache.peek("photo-source-key:rev1"))
        original.recycle()
    }
    @Test fun invalidOrRecycledImageIsNeverOfferedAsWarmPreview() {
        val original=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        original.recycle()
        CollectionCoverWarmBitmapCache.remember("recycled",original)
        assertNull(CollectionCoverWarmBitmapCache.peek("recycled"))
        assertThrows(IllegalArgumentException::class.java) {
            CollectionCoverWarmBitmapCache.remember("",original)
        }
    }
}
