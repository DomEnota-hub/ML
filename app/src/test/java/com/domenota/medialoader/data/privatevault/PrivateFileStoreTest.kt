package com.domenota.medialoader.data.privatevault

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivateFileStoreTest {
    private lateinit var context: Context
    private lateinit var store: PrivateFileStore

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "private-media").deleteRecursively()
        store = PrivateFileStore(context)
    }

    @Test fun verifiedImportPreservesSourceAndExtension() = runBlocking<Unit> {
        val source = File(context.cacheDir, "public.mp4").apply {
            writeBytes(ByteArray(200_123) { (it % 251).toByte() })
        }
        val uri = store.importUri("record-1.mp4", Uri.fromFile(source).toString())
        assertTrue(store.owns(uri))
        assertEquals("record-1.mp4", store.file(uri).name)
        assertArrayEquals(source.readBytes(), store.file(uri).readBytes())
        assertTrue(source.exists())
        assertEquals(source.length(), store.byteCount(uri))
        assertEquals(listOf("record-1.mp4"), store.file(uri).parentFile!!.list()!!.toList())
    }

    @Test fun importWithoutExtensionUsesBinAndSameBytesCanBeRetried() = runBlocking<Unit> {
        val source = File(context.cacheDir, "plain").apply { writeText("keep these bytes") }
        val first = store.importFile("record", source)
        assertEquals("record.bin", store.file(first).name)
        assertEquals(first, store.importFile("record", source))
    }

    @Test fun existingDifferentBytesAreNeverOverwritten() = runBlocking<Unit> {
        val source = File(context.cacheDir, "original").apply { writeText("original") }
        val uri = store.importFile("record.mp4", source)
        source.writeText("replacement")
        try {
            store.importFile("record.mp4", source)
            fail("Different content must fail")
        } catch (_: IOException) {
            assertEquals("original", store.file(uri).readText())
            assertEquals("replacement", source.readText())
        }
    }

    @Test fun rejectsTraversalSiblingPrefixesAndForeignSchemes() = runBlocking<Unit> {
        val source = File(context.cacheDir, "source").apply { writeText("x") }
        for (id in listOf("../escape.mp4", "/absolute.mp4", "a/b", "..", "a..mp4")) {
            try {
                store.importFile(id, source)
                fail("Unsafe id accepted: $id")
            } catch (_: IllegalArgumentException) { }
        }
        val uri = store.importFile("safe.mp4", source)
        val root = store.file(uri).parentFile!!
        assertFalse(store.owns(Uri.fromFile(File(root.path + "-other", "safe.mp4")).toString()))
        assertFalse(store.owns("content://foreign/safe.mp4"))
        assertFalse(store.owns(Uri.fromFile(root).toString()))
        assertFalse(store.owns("file://${root.path}/../private-media/safe.mp4"))
        assertThrows(IllegalArgumentException::class.java) { store.file("file:///etc/passwd") }
    }

    @Test fun symlinkEscapingPrivateDirectoryIsNotOwned() = runBlocking<Unit> {
        val source = File(context.cacheDir, "outside.mp4").apply { writeText("outside") }
        val root = store.file(store.importFile("safe.mp4", source)).parentFile!!
        val link = File(root, "escape.mp4")
        Files.createSymbolicLink(link.toPath(), source.toPath())
        assertFalse(store.owns(Uri.fromFile(link).toString()))
        assertThrows(IllegalArgumentException::class.java) { store.file(Uri.fromFile(link).toString()) }
    }

    @Test fun failedPublicDeletionRetainsPrivateCopyAndRejectsDirectories() = runBlocking<Unit> {
        val source = File(context.cacheDir, "source.mp4").apply { writeText("saved") }
        val uri = store.importFile("safe.mp4", source)
        val directory = File(context.cacheDir, "public-directory").apply { mkdirs() }
        assertFalse(store.removePublicSource(Uri.fromFile(directory).toString()))
        ShadowContentResolver.registerProviderInternal("denied", DeniedProvider())
        assertFalse(store.removePublicSource("content://denied/item/1"))
        assertFalse(store.removePublicSource(uri))
        assertEquals("saved", store.file(uri).readText())
    }

    @Test fun unreadableSourceLeavesCommittedFileAndNoTemporaryCopy() = runBlocking<Unit> {
        val source = File(context.cacheDir, "committed.mp4").apply { writeText("committed") }
        val committed = store.importFile("committed.mp4", source)
        val missing = File(context.cacheDir, "missing-source.mp4").apply { delete() }
        try {
            store.importUri("new.mp4", Uri.fromFile(missing).toString())
            fail("Missing source must fail")
        } catch (_: IOException) {
            assertEquals("committed", store.file(committed).readText())
            assertEquals(listOf("committed.mp4"), store.file(committed).parentFile!!.list()!!.toList())
        }
    }

    @Test fun publicDeletionAndPrivateRemovalAreIdempotent() = runBlocking<Unit> {
        val source = File(context.cacheDir, "deletable.mp4").apply { writeText("saved") }
        val publicUri = Uri.fromFile(source).toString()
        val privateUri = store.importFile("safe.mp4", source)
        assertTrue(store.removePublicSource(publicUri))
        assertTrue(store.removePublicSource(publicUri))
        assertTrue(store.file(privateUri).exists())
        assertTrue(store.remove(privateUri))
        assertTrue(store.remove(privateUri))
        assertEquals(0L, store.byteCount(privateUri))
    }

    /** An actual denied provider; an unregistered Robolectric URI may report delete success. */
    private class DeniedProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = throw SecurityException("Read permission denied")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            throw SecurityException("Delete permission denied")
        override fun insert(uri: Uri, values: ContentValues?): Uri? =
            throw SecurityException("Write permission denied")
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = throw SecurityException("Write permission denied")
    }

}
