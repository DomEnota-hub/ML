package com.domenota.medialoader.data.library

import android.content.Context
import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LastCollectionCoverStoreTest {
    private val context=RuntimeEnvironment.getApplication() as Context
    private val root=File(context.noBackupFilesDir,"last-collection-covers-v1")
    private val store=LastCollectionCoverStore(context)
    private val metadata=LastCollectionCover("slot","version-1","content://media/external/images/media/11",setOf("media:external:11"),1L)
    @After fun clean(){root.deleteRecursively();LastCollectionCoverStore.clearMemory()}
    private fun image()=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888)

    @Test fun processRestartAndDeniedSourcePermissionKeepPrivateLastFrame() {
        store.save("collection",metadata,image())
        LastCollectionCoverStore.clearMemory()
        val afterRestart=LastCollectionCoverStore(context).read("collection")!!
        assertEquals(metadata,afterRestart.metadata)
        assertTrue(afterRestart.bitmap.width<=384 && afterRestart.bitmap.height<=384)
        assertTrue(lastCollectionCoverAllowed(afterRestart.metadata,"slot",emptySet()))
        // No source resolver or READ_MEDIA check is involved in reading the copy.
        assertTrue(root.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
    }
    @Test fun interruptedAtomicReplacementRollsBackToCompletePreviousFrame() {
        store.save("collection",metadata,image())
        val file=File(root,"collection.cover")
        val bytes=file.readBytes()
        // Android AtomicFile recovery keeps base, discards an incomplete .new.
        File(root,"collection.cover.new").writeBytes(byteArrayOf(1,2,3))
        LastCollectionCoverStore.clearMemory()
        assertEquals(metadata,LastCollectionCoverStore(context).read("collection")!!.metadata)
        assertArrayEquals(bytes,file.readBytes())
        assertTrue(file.renameTo(File(root,"collection.cover.bak")))
        file.writeBytes(byteArrayOf(7,8))
        LastCollectionCoverStore.clearMemory()
        assertEquals(metadata,LastCollectionCoverStore(context).read("collection")!!.metadata)
        assertArrayEquals(bytes,file.readBytes())
        // An encoder failure must never publish its metadata or delete the base.
        val recycled=image().apply{recycle()}
        assertTrue(runCatching{store.save("collection",metadata.copy(contentToken="bad"),recycled)}.isFailure)
        assertEquals(metadata,store.read("collection")!!.metadata)
    }
    @Test fun unknownPrivacyHiddenAliasChangedSlotAndUnknownIdentityFailClosed() {
        assertFalse(lastCollectionCoverAllowed(metadata,"slot",null))
        assertFalse(lastCollectionCoverAllowed(metadata,"slot",setOf("media:external:11")))
        assertFalse(lastCollectionCoverAllowed(metadata,"changed-member",emptySet()))
        assertFalse(lastCollectionCoverAllowed(metadata.copy(identities=emptySet()),"slot",emptySet()))
        assertTrue(lastCollectionCoverAllowed(metadata,"slot",emptySet()))
    }
    @Test fun changedContentReplacesOnlyAfterSuccessfulPreparationAndUnchangedContentDoesNotWrite() {
        store.save("collection",metadata,image())
        val file=File(root,"collection.cover")
        val original=file.readBytes()
        assertFalse(store.save("collection",metadata,image()))
        assertArrayEquals(original,file.readBytes())
        val replacement=metadata.copy(contentToken="version-2",lastVerifiedAt=2L)
        assertTrue(store.save("collection",replacement,image()))
        LastCollectionCoverStore.clearMemory()
        assertEquals(replacement,store.read("collection")!!.metadata)
        store.remove("collection")
        assertNull(store.read("collection"))
    }
    @Test fun finiteDiskBudgetEvictsOldestWithoutTouchingSourceOrOtherDirectories() {
        val bounded=LastCollectionCoverStore(context,maxEntries=2,maxBytes=1024L*1024)
        bounded.save("first",metadata,image())
        File(root,"first.cover").setLastModified(1L)
        bounded.save("second",metadata,image())
        File(root,"second.cover").setLastModified(2L)
        bounded.save("third",metadata,image())
        assertNull(bounded.read("first"))
        assertNotNull(bounded.read("second"));assertNotNull(bounded.read("third"))
        assertEquals(2,root.listFiles()!!.count{it.extension=="cover"})
    }
    @Test fun corruptOversizedAndUnknownHeadersAreNeverDecodedOrPublished() {
        root.mkdirs()
        File(root,"corrupt.cover").writeBytes(byteArrayOf(1,2,3))
        assertNull(store.read("corrupt"))
        val oversized=File(root,"large.cover")
        java.io.RandomAccessFile(oversized,"rw").use{it.setLength(1024L*1024)}
        assertNull(store.read("large"))
    }
    @Test fun encodedByteBudgetEvictsOldFramesAndKeepsTheNewestCompleteOne() {
        val budget=600L*1024
        val bounded=LastCollectionCoverStore(context,maxEntries=512,maxBytes=budget)
        val random=java.util.Random(203L)
        val noise=Bitmap.createBitmap(384,384,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(384*384){random.nextInt() or 0xff000000.toInt()}
        noise.setPixels(pixels,0,384,0,0,384,384)
        repeat(10){index->
            bounded.save("bytes-$index",metadata,noise)
            File(root,"bytes-$index.cover").setLastModified(index+1L)
        }
        assertTrue(root.listFiles()!!.filter{it.extension=="cover"}.sumOf{it.length()}<=budget)
        assertNull(bounded.read("bytes-0"))
        assertNotNull(bounded.read("bytes-9"))
        noise.recycle()
    }

    @Test fun composePeekNeverWaitsForTheSerializedDiskWriter() {
        store.save("collection",metadata,image())
        // Hold the exact lock used by read/save/remove without injecting a
        // production pause callback. Only latch ordering matters, not IO speed.
        val field=LastCollectionCoverStore::class.java.getDeclaredField("ioLock").apply{isAccessible=true}
        val ioLock=requireNotNull(field.get(null))
        val held=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val peekDone=java.util.concurrent.CountDownLatch(1)
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val writer=pool.submit {
                synchronized(ioLock){held.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS))}
            }
            assertTrue(held.await(5,java.util.concurrent.TimeUnit.SECONDS))
            val reader=pool.submit<LastCollectionCoverFrame?> { store.peek("collection").also{peekDone.countDown()} }
            // The timeout bounds a deadlock failure; it is not a latency budget.
            assertTrue("A Compose cache peek must finish while the IO lock is still held",
                peekDone.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(metadata,reader.get()!!.metadata)
            release.countDown();writer.get()
        }finally{release.countDown();pool.shutdownNow()}
    }

    @Test fun selectingAutomaticKeepsTheAcceptedFrameButDeletingCollectionPurgesIt() {
        store.save("collection",metadata,image())
        val selections=CollectionCoverStore(context)
        selections.automatic("collection")
        LastCollectionCoverStore.clearMemory()
        assertEquals(metadata,store.read("collection")!!.metadata)
        selections.delete("collection")
        assertNull(store.peek("collection"))
        assertNull(store.read("collection"))
        assertFalse(File(root,"collection.cover").exists())
    }

}
