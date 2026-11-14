package com.domenota.medialoader.data.library

import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import com.domenota.medialoader.ui.screens.organization.CollectionCrop
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class CollectionCoverStoreTest {
    private val context=RuntimeEnvironment.getApplication() as Context
    private val store=CollectionCoverStore(context)
    @After fun clean(){store.automatic("cover-test");File(context.cacheDir,"cover-source.jpg").delete()}
    @Test fun committedCoverAndCropSurviveSourceRemovalAndReplacementCleansOldCopy()=runBlocking {
        val source=File(context.cacheDir,"cover-source.jpg")
        val image=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888)
        source.outputStream().use{image.compress(Bitmap.CompressFormat.JPEG,90,it)}
        val uri=Uri.fromFile(source).toString()
        store.save("cover-test",uri,image,CollectionCrop(.6f,.5f,2f))
        val old=store.read("cover-test")!!
        assertTrue(old.file.canonicalPath.startsWith(context.filesDir.canonicalPath))
        assertFalse(old.file.canonicalPath.startsWith(context.cacheDir.canonicalPath))
        source.delete()
        val reloaded=CollectionCoverStore(context).read("cover-test")!!
        assertEquals(old.crop,reloaded.crop)
        assertTrue(reloaded.file.isFile)
        store.save("cover-test",uri,image,CollectionCrop())
        assertFalse(old.file.exists())
        val current=store.read("cover-test")!!
        val result=android.graphics.BitmapFactory.decodeFile(current.file.path)
        assertEquals(640,result.width);assertEquals(800,result.height)
        result.recycle();image.recycle()
        store.automatic("cover-test")
        assertNull(store.read("cover-test"));assertFalse(current.file.exists())
    }
    @Test fun decodeAppliesExifBeforeCropGeometry() {
        val source=File(context.cacheDir,"cover-source.jpg")
        val image=Bitmap.createBitmap(100,50,Bitmap.Config.ARGB_8888)
        source.outputStream().use{image.compress(Bitmap.CompressFormat.JPEG,90,it)}
        ExifInterface(source.path).apply{setAttribute(ExifInterface.TAG_ORIENTATION,"6");saveAttributes()}
        val decoded=store.decode(Uri.fromFile(source).toString())
        assertEquals(50,decoded.width);assertEquals(100,decoded.height)
        decoded.recycle();image.recycle()
    }
    @Test fun invalidSourceDoesNotRemoveCommittedCover()=runBlocking {
        val image=Bitmap.createBitmap(40,50,Bitmap.Config.ARGB_8888)
        val missing=File(context.cacheDir,"absent-cover-source.jpg").apply{delete()}
        val missingUri=Uri.fromFile(missing).toString()
        store.save("cover-test",missingUri,image,CollectionCrop())
        val before=store.read("cover-test")!!
        assertTrue(runCatching{store.decode(missingUri)}.isFailure)
        assertEquals(before,store.read("cover-test"));assertTrue(before.file.isFile)
        image.recycle()
    }
}
