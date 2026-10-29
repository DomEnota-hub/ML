package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneLibraryCacheTest {
    private val mode = AppMode.GALLERY
    private val stamp = PhoneStoreStamp("primary:database-v1", "primary:12")
    private val uri = "content://media/external/images/media/42"
    private fun snapshot() = PhoneMediaSnapshot(listOf(DownloadEntity("phone:image:42", "device", "photo.jpg",
        MediaType.PHOTO, DownloadState.COMPLETED, uri, 123L, 456L, previewUrl = uri,
        groupId = "Camera", bulkMediaKey = "7")), mapOf(uri to setOf("media:external:42", "file-stat:identity")),
        mapOf(uri to "fingerprint-42"))

    @Test fun freshInstanceRestoresMetadataPhysicalAliasesAndGeneration() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val original = snapshot()
        PhoneLibraryCache(context).write(mode, "images", "privacy", stamp, original)
        val restored = PhoneLibraryCache(context).read(mode, "images", "privacy", stamp)
        assertEquals(original, restored?.snapshot)
        assertEquals(stamp, restored?.stamp)
        assertFalse(File(context.filesDir, "phone-library-v1").exists())
    }
    @Test fun revokedAccessChangedPrivateKeysAndDatabaseResetCannotPublishOldSnapshot() = runBlocking {
        val cache = PhoneLibraryCache(RuntimeEnvironment.getApplication())
        cache.write(mode, "images", "privacy", stamp, snapshot())
        assertNull(cache.read(mode, "none", "privacy", stamp))
        assertNull(cache.read(mode, "images", "new-private-keys", stamp))
        assertNull(cache.read(mode, "images", "privacy", stamp.copy(versions = "new-database")))
        assertNull(cache.read(mode, "images", "privacy", stamp.copy(versions = null)))
    }
    @Test fun newerGenerationRetainsOldValidatedListForBackgroundReconciliation() = runBlocking {
        val cache = PhoneLibraryCache(RuntimeEnvironment.getApplication())
        cache.write(mode, "images", "privacy", stamp, snapshot())
        val restored = cache.read(mode, "images", "privacy", stamp.copy(generation = "primary:13"))
        assertEquals(snapshot(), restored?.snapshot)
        assertEquals("primary:12", restored?.stamp?.generation)
    }
    @Test fun corruptionFallsBackToScanAndAtomicReplacementRecovers() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.noBackupFilesDir, "phone-library-v1/gallery.json")
        file.parentFile!!.mkdirs()
        file.writeText("broken")
        val cache = PhoneLibraryCache(context)
        assertNull(cache.read(mode, "images", "privacy", stamp))
        cache.write(mode, "images", "privacy", stamp, snapshot())
        assertEquals(snapshot(), cache.read(mode, "images", "privacy", stamp)?.snapshot)
    }
    @Test fun emptyReconciledSnapshotReplacesDeletedRows() = runBlocking {
        val cache = PhoneLibraryCache(RuntimeEnvironment.getApplication())
        cache.write(mode, "images", "privacy", stamp, snapshot())
        cache.write(mode, "images", "privacy", stamp.copy(generation = "primary:13"), PhoneMediaSnapshot(emptyList(), emptyMap()))
        assertTrue(cache.read(mode, "images", "privacy", stamp)?.snapshot?.items?.isEmpty() == true)
        cache.clear(mode)
        assertNull(cache.read(mode, "images", "privacy", stamp))
    }
    @Test fun privacyDigestIsOrderIndependentButChangesWithIdentitySet() {
        assertEquals(PhoneLibraryCache.privacyTag(setOf("a", "b")), PhoneLibraryCache.privacyTag(setOf("b", "a")))
        assertNotEquals(PhoneLibraryCache.privacyTag(setOf("a", "b")), PhoneLibraryCache.privacyTag(setOf("a")))
    }
    @Test @Config(shadows = [CatalogMediaStore::class])
    fun coldViewModelPublishesDiskListBeforeBlockedBackgroundScanAndThenReconciles() {
        val app = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.READ_MEDIA_IMAGES)
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        CatalogMediaStore.generation = 1
        val provider = DelayedMediaProvider()
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", provider)
        val model = com.domenota.medialoader.ui.LibraryViewModel(app)
        val owner = androidx.lifecycle.ViewModelStore().apply { put("library", model) }
        fun awaitReady(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!predicate() && System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertTrue("Async library state did not arrive", predicate())
        }
        try {
            awaitReady { model.privateKeys.value != null }
            runBlocking {
                val currentStamp = PhoneMediaLibrary(app).storeStamp()
                assertNotNull(currentStamp.versions)
                PhoneLibraryCache(app).write(mode, android.Manifest.permission.READ_MEDIA_IMAGES,
                    PhoneLibraryCache.privacyTag(model.privateKeys.value!!), currentStamp, snapshot())
            }
            CatalogMediaStore.generation = 2
            model.refresh(mode)
            model.refresh(mode) // Entry and resume effects must coalesce, preserving disk restore.
            awaitReady { model.gallery.value.loaded && model.index.value.refs.any { it.key == "phone:image:42" } }
            assertEquals(listOf("phone:image:42"), model.gallery.value.items.map { it.id })
            assertFalse(model.gallery.value.requiresValidation)
            assertFalse(com.domenota.medialoader.ui.phoneValidationPending(model.index.value, model.gallery.value, model.music.value))
            provider.release.countDown()
            awaitReady { !model.gallery.value.loading && model.gallery.value.items.any { it.id == "phone:image:43" } }
            assertEquals(listOf("phone:image:43"), model.gallery.value.items.map { it.id })
        } finally {
            provider.release.countDown()
            owner.clear()
        }
    }

    class DelayedMediaProvider : android.content.ContentProvider() {
        val release = java.util.concurrent.CountDownLatch(1)
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle =
            android.os.Bundle().apply { putString("version", "test-database") }
        override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): android.database.Cursor {
            val columns = requireNotNull(projection)
            val result = android.database.MatrixCursor(columns)
            if (uri.toString().contains("/images/")) {
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                result.addRow(columns.map { when(it) {
                    "_id" -> 43L
                    "_display_name" -> "new.jpg"
                    "_size" -> 5L
                    "date_added", "date_modified" -> 100L
                    else -> null
                } })
            }
            return result
        }
        override fun getType(uri: android.net.Uri): String? = null
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = error("read only")
        override fun update(uri: android.net.Uri, values: android.content.ContentValues?, where: String?, args: Array<out String>?): Int = error("read only")
        override fun delete(uri: android.net.Uri, where: String?, args: Array<out String>?): Int = error("read only")
    }

}

@org.robolectric.annotation.Implements(android.provider.MediaStore::class)
class CatalogMediaStore {
    companion object {
        var generation = 1L
        @JvmStatic @org.robolectric.annotation.Implementation
        fun getExternalVolumeNames(context: android.content.Context): Set<String> = setOf("external_primary")
        @JvmStatic @org.robolectric.annotation.Implementation
        fun getVersion(context: android.content.Context, volume: String): String = "test-database"
        @JvmStatic @org.robolectric.annotation.Implementation
        fun getGeneration(context: android.content.Context, volume: String): Long = generation
    }
}
