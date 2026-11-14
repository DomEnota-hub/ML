package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class BatchPhotoPreviewCacheTest {
    @Test fun repeatedKeyReusesPixelsButVersionAndClearInvalidate() = runBlocking {
        val cache=BatchPhotoPreviewCache()
        var decodes=0
        suspend fun load(key:String)=cache.image(key){decodes++;Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888)}
        val first=load("id:version1")
        assertSame(first,load("id:version1"))
        assertNotSame(first,load("id:version2"))
        cache.clear()
        assertNotSame(first,load("id:version1"))
        assertEquals(3,decodes)
        assertFalse(first.isRecycled) // An Image can still reference an evicted entry.
    }
    @Test fun workIsBoundedAndCancelledResultsAreNeverCached() = runBlocking {
        val cache=BatchPhotoPreviewCache()
        val active=AtomicInteger();val maximum=AtomicInteger()
        (0..7).map { id -> async(Dispatchers.Default) {
            cache.image("$id") {
                val count=active.incrementAndGet()
                maximum.updateAndGet{maxOf(it,count)}
                try{delay(15);Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)}
                finally{active.decrementAndGet()}
            }
        } }.awaitAll()
        assertTrue(maximum.get()<=2)
        val started=CompletableDeferred<Unit>()
        val cancelled=launch {cache.image("cancelled"){started.complete(Unit);awaitCancellation()}}
        started.await();cancelled.cancelAndJoin()
        var retried=false
        cache.image("cancelled"){retried=true;Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)}
        assertTrue(retried)
    }
}
