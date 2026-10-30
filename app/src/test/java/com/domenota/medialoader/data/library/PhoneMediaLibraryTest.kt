package com.domenota.medialoader.data.library

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneMediaLibraryTest {
    @Test fun permissionsAreLimitedToSelectedModeAndPlatform() {
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), PhoneMediaLibrary.permissions(AppMode.GALLERY, 32))
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_AUDIO), PhoneMediaLibrary.permissions(AppMode.MUSIC, 34))
        assertTrue(PhoneMediaLibrary.permissions(AppMode.GALLERY, 34).contains(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        assertTrue(PhoneMediaLibrary.permissions(AppMode.DOWNLOADER, 34).isEmpty())
    }

    @Test fun partialAndRevokedPermissionsGateEachCollection() {
        val images = setOf(Manifest.permission.READ_MEDIA_IMAGES)
        assertTrue(PhoneMediaLibrary.canRead(MediaType.PHOTO, 34, images::contains))
        assertFalse(PhoneMediaLibrary.canRead(MediaType.VIDEO, 34, images::contains))
        assertFalse(PhoneMediaLibrary.canRead(MediaType.AUDIO, 34, images::contains))
        val selected = setOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertTrue(PhoneMediaLibrary.canRead(MediaType.PHOTO, 34, selected::contains))
        assertTrue(PhoneMediaLibrary.canRead(MediaType.VIDEO, 34, selected::contains))
        assertFalse(PhoneMediaLibrary.canRead(MediaType.PHOTO, 34) { false })
    }

    @Test fun scanQueriesOnlyGrantedCollectionAndClearsOnRevocation() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val provider = RecordingProvider()
        ShadowContentResolver.registerProviderInternal("media", provider)
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val library = PhoneMediaLibrary(app)
        assertEquals(listOf("phone:image:42"), library.scan(AppMode.GALLERY).map { it.id })
        assertEquals(listOf("content://media/external/images/media"), provider.queries)
        shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        assertTrue(library.scan(AppMode.GALLERY).isEmpty())
        assertEquals(1, provider.queries.size)
    }

    // Robolectric's default Linux.stat/fstat return inode=0, so model the OS metadata
    // contract explicitly. Provider calls and real scanner/index identities remain under test.
    @Config(shadows = [PhysicalStatOs::class])
    @Test fun scanCapturesPhysicalAliasesInTheCollectionQueryWithoutOpeningEachMediaFile() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val file = java.io.File(app.cacheDir, "indexed-photo.jpg").apply { writeText("photo") }
        val provider = RecordingProvider(file.absolutePath)
        ShadowContentResolver.registerProviderInternal("media", provider)
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val snapshot = PhoneMediaLibrary(app).scanSnapshot(AppMode.GALLERY)
        val uri = requireNotNull(snapshot.items.single().savedUri)
        val expected = com.domenota.medialoader.data.playback.PublicMediaIdentity.capture(app, file.toURI().toString())
        val aliases = snapshot.identities.getValue(uri)
        assertTrue(aliases.containsAll(com.domenota.medialoader.data.playback.PublicMediaIdentity.uriKeys(uri)))
        assertTrue("Physical alias must still hide SAF/private copies", aliases.intersect(expected).any { it.startsWith("file-stat:") })
        assertEquals("One collection query, no per-file provider round trips", 1, provider.queries.size)
        assertEquals(0, provider.fileOpens)
        file.delete()
        Unit
    }

    @Config(shadows = [PhysicalStatOs::class])
    @Test fun reconciliationReusesUnchangedIdentitiesAndDropsDeletedRows() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val provider = RecordingProvider("/storage/emulated/0/Pictures/photo.jpg")
        ShadowContentResolver.registerProviderInternal("media", provider)
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val library = PhoneMediaLibrary(app)
        val first = library.scanSnapshot(AppMode.GALLERY)
        val calls = PhysicalStatOs.statCalls
        val second = library.scanSnapshot(AppMode.GALLERY, first)
        assertEquals(first, second)
        assertEquals(calls, PhysicalStatOs.statCalls)
        provider.modified++
        val changed = library.scanSnapshot(AppMode.GALLERY, second)
        assertTrue(PhysicalStatOs.statCalls > calls)
        assertNotEquals(second.fingerprints, changed.fingerprints)
        provider.present = false
        val deleted = library.scanSnapshot(AppMode.GALLERY, changed)
        assertTrue(deleted.items.isEmpty())
        assertTrue(deleted.identities.isEmpty())
        assertTrue(deleted.fingerprints.isEmpty())
        assertEquals(0, provider.fileOpens)
    }

    @Test fun firstScanPublishesFirstPageWithoutOpeningEveryFileEvenWhenPathsAreUnavailable() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val provider = RecordingProvider(rowCount = 257)
        ShadowContentResolver.registerProviderInternal("media", provider)
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val sizes = mutableListOf<Int>()
        val snapshot = PhoneMediaLibrary(app).scanSnapshot(AppMode.GALLERY, onProgress = { sizes += it.items.size })
        assertEquals(64, sizes.first())
        assertEquals(257, snapshot.items.size)
        assertEquals(1, provider.queries.size)
        assertEquals(0, provider.fileOpens)
    }

    private class RecordingProvider(private val path: String? = null, private val rowCount: Int = 1) : ContentProvider() {
        val queries = mutableListOf<String>()
        var fileOpens = 0
        var modified = 123L
        var present = true
        override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
            fileOpens++
            error("Collection indexing must not open individual files")
        }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queries += uri.toString()
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).apply {
                if (present) repeat(rowCount) { offset -> addRow(columns.map { column -> when (column) {
                    "_id" -> 42L + offset
                    "_display_name" -> "photo.jpg"
                    "_size" -> 100L
                    "_data" -> path
                    "date_added" -> 123L
                    "date_modified" -> modified
                    "bucket_display_name" -> "Camera"
                    else -> null
                } }) }
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No media insert allowed")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("No media delete allowed")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("No media update allowed")
    }

    @Test fun cursorMapsTypedIdentityUriAlbumAndDurationWithoutImport() {
        MatrixCursor(arrayOf("_id", "_display_name", "_size", "date_added", "duration", "bucket_display_name", "title", "artist")).use { cursor ->
            cursor.addRow(arrayOf(42L, "clip.mp4", 123L, 100L, 2500L, "Camera", "Clip", "Artist"))
            cursor.moveToFirst()
            val video = PhoneMediaLibrary.mapRow(cursor, MediaType.VIDEO, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            assertEquals("phone:video:42", video.id)
            assertEquals("device", video.providerId)
            assertEquals("content://media/external/video/media/42", video.savedUri)
            assertEquals("Camera", video.groupId)
            assertEquals(2500L, video.durationMs)
            assertEquals(100_000L, video.createdAtEpochMillis)
            assertFalse(video.isPrivate)
            val audio = PhoneMediaLibrary.mapRow(cursor, MediaType.AUDIO, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            assertEquals("phone:audio:42", audio.id)
            assertEquals("Artist", audio.sourceAudioArtist)
            assertEquals("Clip", audio.sourceAudioTitle)
        }
    }
}

@org.robolectric.annotation.Implements(android.system.Os::class)
class PhysicalStatOs {
    companion object {
        var statCalls = 0
        @JvmStatic @org.robolectric.annotation.Implementation
        fun stat(path: String): android.system.StructStat {
            statCalls++
            return android.system.StructStat(
            7L, 42L, android.system.OsConstants.S_IFREG, 1L, 0, 0, 0L, 5L,
            123L, 123L, 123L, 4096L, 1L)
        }
        @JvmStatic @org.robolectric.annotation.Implementation
        fun fstat(descriptor: java.io.FileDescriptor): android.system.StructStat = stat("")
    }
}
