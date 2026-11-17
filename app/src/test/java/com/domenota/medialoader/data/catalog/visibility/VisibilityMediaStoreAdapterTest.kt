package com.domenota.medialoader.data.catalog.visibility

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.room.Room
import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import com.domenota.medialoader.core.database.CatalogFolderEntity
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.StorageVolumeBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [VisibilityAdapterStatOs::class])
class VisibilityMediaStoreAdapterTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: MediaProvider
    private lateinit var media: VisibilityMediaStore
    private lateinit var source: File
    private lateinit var alias: File
    private lateinit var root: File

    @Before fun setUp() {
        // Robolectric can race its asynchronous SharedPreferences load against a
        // missing synthetic file. Seed an empty valid catalog before creating the
        // production repository; the real Android store is not changed.
        val preferencesDir = File(app.applicationInfo.dataDir, "shared_prefs")
        assertTrue(preferencesDir.isDirectory || preferencesDir.mkdirs())
        File(preferencesDir, "external_media_catalog.xml").apply {
            if (!exists()) writeText("<map />", Charsets.UTF_8)
        }
        root = Files.createTempDirectory(app.cacheDir.toPath(), "visibility-adapter-").toFile()
        source = File(root, "Pictures/photo.jpg").apply {
            parentFile!!.mkdirs(); writeText("synthetic original"); setLastModified(1_700_000_000_000L)
        }
        alias = File(root, "primary-alias")
        Files.createSymbolicLink(alias.toPath(), source.parentFile!!.toPath())
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        val manager = app.getSystemService(StorageManager::class.java)
        shadowOf(manager).resetStorageVolumeList()
        shadowOf(manager).addStorageVolume(StorageVolumeBuilder("primary", root, "Synthetic", Process.myUserHandle(),
            Environment.MEDIA_MOUNTED).setIsPrimary(true).setIsEmulated(true).build())
        assertTrue(MediaStore.VOLUME_EXTERNAL_PRIMARY in MediaStore.getExternalVolumeNames(app))
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION)
        provider = MediaProvider(source)
        provider.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
        ShadowContentResolver.registerProviderInternal("media", provider)
        media = VisibilityMediaStore(app)
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun canonicalFileAliasFindsExactRowAndReadsOriginalBytes() = runBlocking {
        val origin = media.captureCatalog(Uri.fromFile(File(alias, source.name)).toString(), "image/*")
        assertEquals(source.path, origin.physicalPath)
        assertEquals("content://media/external_primary/images/media/42", origin.uri)
        assertEquals(HiddenFolderFileStore.fingerprint(source.inputStream()),
            HiddenFolderFileStore.fingerprint(media.openOriginal(origin)))
        assertTrue(source.exists())
        assertEquals(0, provider.mutations)
    }

    @Test fun samePathReplacementAfterAliasCaptureCannotAuthorizeDifferentPhysicalFile() = runBlocking {
        provider.beforeExactRead = {
            val replacement = File(source.parentFile, "replacement.tmp").apply {
                writeBytes(source.readBytes()); setLastModified(source.lastModified())
            }
            Files.move(replacement.toPath(), source.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        expectFailure("Адрес MediaStore относится к другому файлу") {
            media.captureCatalog(Uri.fromFile(File(alias, source.name)).toString(), "image/*")
        }
        assertTrue(source.exists())
        assertEquals(0, provider.mutations)
    }

    @Test fun revokedPermissionStopsAliasCaptureBeforeProviderAccess() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        expectFailure("Требуется полный доступ") {
            media.captureCatalog(Uri.fromFile(File(alias, source.name)).toString(), "image/*")
        }
        assertEquals(0, provider.queries)
        assertTrue(source.exists())
    }

    @Test fun deniedProviderCannotBeInterpretedAsUnindexedDestination() = runBlocking {
        provider.denyQueries = true
        try {
            media.verifyHiddenNotIndexed(source, "image/jpeg")
            fail("Provider denial must block verification")
        } catch (_: SecurityException) { }
        assertTrue(source.exists())
        assertEquals(0, provider.mutations)
    }

    @Test fun unrelatedIndexedPathDoesNotBlockExactDestinationAbsence() = runBlocking {
        val other = File(source.parentFile, "other.jpg").apply { writeText("unrelated") }
        media.verifyHiddenNotIndexed(other, "image/jpeg")
        assertTrue(other.exists())
        assertEquals(0, provider.mutations)
        assertTrue(provider.includedPendingAndTrashed)
    }

    @Test fun hiddenFileIndexedUnderWrongMediaTypeBlocksVerification() = runBlocking {
        // A stale video index row must also block hiding an image at this exact path.
        provider.kind = "video"
        provider.pending = true
        provider.trashed = true
        expectFailure("распознаёт скрытый файл как медиа") {
            media.verifyHiddenNotIndexed(source, "image/jpeg")
        }
        assertTrue(source.exists())
        assertEquals(0, provider.mutations)
        assertTrue(provider.includedPendingAndTrashed)
    }

    @Test fun unclassifiedFilesRowDoesNotBlockVerifiedHiddenCopy() = runBlocking {
        provider.kind = "file"
        provider.mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
        media.verifyHiddenNotIndexed(source, "image/jpeg")
        assertTrue(source.exists()); assertEquals(0, provider.mutations)
        assertTrue(provider.publicCollectionsChecked.containsAll(setOf("images", "video", "audio")))
    }

    @Test fun unclassifiedFilesRowButPublishedImageBlocksVerification() = runBlocking {
        provider.mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
        provider.visibleInImages = true
        expectFailure("публичной медиатеке") { media.verifyHiddenNotIndexed(source, "image/jpeg") }
        assertTrue(source.exists()); assertEquals(0, provider.mutations)
    }

    @Test fun pendingMediaClassifiedFilesRowRemainsFailClosed() = runBlocking {
        provider.kind = "file"
        provider.mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        provider.pending = true
        expectFailure("распознаёт скрытый файл как медиа") { media.verifyHiddenNotIndexed(source, "image/jpeg") }
        assertTrue(source.exists()); assertEquals(0, provider.mutations)
    }

    @Test fun legacyBindingEnrichmentPreservesExactIdentityAndChecksOriginalHash() = runBlocking {
        legacyCatalog { bindings, record, db ->
            val upgraded = bindings.enrichLegacy(record.copy(sourceModifiedMillis = null), media)
            assertEquals(record.logicalSourceUri, upgraded.logicalSourceUri)
            assertEquals(record.fingerprint, upgraded.fingerprint)
            assertEquals(record.operationId, upgraded.operationId)
            assertEquals(record.destinationToken, upgraded.destinationToken)
            assertEquals(source.lastModified(), upgraded.sourceModifiedMillis)
            assertEquals(source.path, upgraded.publicOrigin!!.physicalPath)
            assertEquals(record.logicalSourceUri, db.downloadDao().byId(record.downloadId)!!.savedUri)
            assertTrue(source.exists()); assertEquals(0, provider.mutations)
        }
    }

    @Test fun legacyBindingRejectsChangedBytesAndKnownDate() = runBlocking {
        legacyCatalog { bindings, record, _ ->
            expectFailure("SHA-256") {
                bindings.enrichLegacy(record.copy(fingerprint = VisibilityFingerprint(source.length(), "b".repeat(64))), media)
            }
            expectFailure("SHA-256") {
                bindings.enrichLegacy(record.copy(sourceModifiedMillis = record.sourceModifiedMillis!! + 1000L), media)
            }
            assertTrue(source.exists()); assertEquals(0, provider.mutations)
        }
    }

    @Test fun legacyBindingRejectsStaleCatalogAndMissingExactSource() = runBlocking {
        legacyCatalog { bindings, record, db ->
            val original = requireNotNull(db.downloadDao().byId(record.downloadId))
            db.downloadDao().upsert(original.copy(savedUri = "file:///synthetic/unrelated.jpg"))
            expectFailure("Запись старого переноса изменилась") { bindings.enrichLegacy(record, media) }
            db.downloadDao().upsert(original)
            provider.present = false
            expectFailure("Не удалось точно подтвердить исходник старого переноса") { bindings.enrichLegacy(record, media) }
            assertTrue(source.exists()); assertEquals(0, provider.mutations)
        }
    }

    @Test fun legacyBindingRejectsAmbiguousMediaStoreAlias() = runBlocking {
        legacyCatalog { bindings, record, _ ->
            provider.duplicatePath = true
            expectFailure("Не удалось точно подтвердить исходник старого переноса") { bindings.enrichLegacy(record, media) }
            assertTrue(source.exists()); assertEquals(0, provider.mutations)
        }
    }

    private suspend fun legacyCatalog(action: suspend (FolderVisibilityCatalogBindings, VisibilityMoveRecord, HistoryDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(app, HistoryDatabase::class.java).build()
        try {
            val uri = Uri.fromFile(File(alias, source.name)).toString()
            val record = VisibilityMoveRecord("legacy-operation", "legacy-folder", "legacy-member", VisibilityDirection.HIDE,
                uri, source.name, source.lastModified(), HiddenFolderFileStore.fingerprint(source.inputStream()), "legacy-token-00000001")
            db.downloadDao().upsert(DownloadEntity(record.downloadId, "synthetic", source.name, MediaType.PHOTO,
                DownloadState.COMPLETED, uri, source.length(), 123_000L))
            db.catalogDao().insertFolder(CatalogFolderEntity(record.folderId, "Synthetic", "synthetic", 123_000L))
            db.catalogDao().saveAssignment(CatalogAssignmentEntity(record.downloadId, record.folderId, false))
            action(FolderVisibilityCatalogBindings(app, db), record, db)
        } finally { db.close() }
    }

    private suspend fun expectFailure(message: String, action: suspend () -> Unit) {
        try { action(); fail("Expected verification failure: $message") }
        catch (failure: IllegalStateException) { assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(message)) }
    }

    private class MediaProvider(private val file: File) : ContentProvider() {
        var kind = "images"
        var mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        var visibleInImages = false
        val publicCollectionsChecked = mutableSetOf<String>()
        private var excludePendingAndTrashed = false
        var pending = false
        var trashed = false
        var denyQueries = false
        var present = true
        var duplicatePath = false
        var beforeExactRead: (() -> Unit)? = null
        var queries = 0
        var mutations = 0
        var includedPendingAndTrashed = false
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor {
            includedPendingAndTrashed = queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_PENDING) == MediaStore.MATCH_INCLUDE &&
                queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED) == MediaStore.MATCH_INCLUDE
            excludePendingAndTrashed = queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_PENDING) == MediaStore.MATCH_EXCLUDE &&
                queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED) == MediaStore.MATCH_EXCLUDE
            return query(uri, projection, queryArgs?.getString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION),
                queryArgs?.getStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS), null)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queries++
            if (denyQueries) throw SecurityException("Synthetic provider access revoked")
            val exact = uri.lastPathSegment == "42"
            if (exact) beforeExactRead?.also { beforeExactRead = null; it() }
            val columns = requireNotNull(projection)
            val matchesPath = selection == null || selectionArgs?.firstOrNull() == file.path
            val queryKind = uri.pathSegments.getOrNull(1).orEmpty()
            if (excludePendingAndTrashed && queryKind != "file") publicCollectionsChecked += queryKind
            val matchesKind = queryKind in setOf(kind, "file") || visibleInImages && queryKind == "images"
            val publicEligible = !excludePendingAndTrashed || queryKind == "file" || !pending && !trashed
            return MatrixCursor(columns).apply {
                if (present && matchesPath && matchesKind && publicEligible) repeat(if (duplicatePath && !exact) 2 else 1) { offset -> addRow(columns.map { key -> when (key) {
                    "_id" -> 42L + offset
                    MediaStore.Files.FileColumns.MEDIA_TYPE -> mediaType
                    "volume_name" -> MediaStore.VOLUME_EXTERNAL_PRIMARY
                    "relative_path" -> "Pictures/"
                    "_display_name" -> file.name
                    "mime_type" -> if (kind == "video") "video/mp4" else "image/jpeg"
                    "_data" -> file.path
                    "_size" -> file.length()
                    "date_modified", "date_added" -> file.lastModified() / 1000L
                    "datetaken" -> 0L
                    "owner_package_name" -> context!!.packageName
                    "is_pending" -> if (pending) 1 else 0
                    "is_trashed" -> if (trashed) 1 else 0
                    else -> error("Unexpected projection: $key")
                } }) }
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun getType(uri: Uri) = "image/jpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? { mutations++; error("No insert authorized") }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { mutations++; error("No deletion authorized") }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { mutations++; error("No update authorized") }
    }
}

/** Robolectric's default Os.stat has inode zero; retain actual synthetic inode identity. */
@Implements(Os::class)
class VisibilityAdapterStatOs {
    companion object {
        @JvmStatic @Implementation fun stat(path: String): StructStat {
            val file = File(path).canonicalFile
            val inode = (Files.getAttribute(file.toPath(), "unix:ino") as Number).toLong()
            return StructStat(7L, inode, OsConstants.S_IFREG, 1L, 0, 0, 0L, file.length(),
                file.lastModified() / 1000L, file.lastModified() / 1000L, file.lastModified() / 1000L, 4096L, 1L)
        }
    }
}
