package com.domenota.medialoader.data.storage

import androidx.room.Room
import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import com.domenota.medialoader.core.database.CatalogFolderEntity
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyMediaMigrationHistoryTest {
    @Test fun verifiedCopyCanSwapUriWithoutDroppingHistoryFavoriteOrAlbum() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), HistoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = database.downloadDao()
            val catalog = database.catalogDao()
            val before = DownloadEntity(
                id = "download-keep-id",
                providerId = "instagram",
                originalName = "old.jpg",
                mediaType = MediaType.PHOTO,
                state = DownloadState.COMPLETED,
                savedUri = "content://media/external/downloads/42",
                sizeBytes = 4096L,
                createdAtEpochMillis = 123456L,
                sourcePageUrl = "https://example.invalid/post",
                durationMs = null,
            )
            val folder = CatalogFolderEntity(
                id = "album-id", name = "Избранные фото",
                nameKey = "избранные фото", createdAtEpochMillis = 22L,
            )
            dao.upsert(before)
            catalog.insertFolder(folder)
            catalog.saveAssignment(CatalogAssignmentEntity(
                downloadId = before.id, folderId = folder.id, isFavorite = true,
            ))

            assertEquals(1, dao.countSavedUriReferences(before.savedUri!!))
            assertEquals(0, dao.moveSavedUriWhenCurrent(
                before.id, "content://media/external/downloads/wrong",
                "content://media/external/images/media/99", "new.jpg"))
            assertEquals(before, dao.byId(before.id))
            assertEquals(1, dao.moveSavedUriWhenCurrent(
                before.id, before.savedUri!!,
                "content://media/external/images/media/99", "new.jpg"))

            val after = requireNotNull(dao.byId(before.id))
            assertEquals(before.id, after.id)
            assertEquals(before.providerId, after.providerId)
            assertEquals(before.createdAtEpochMillis, after.createdAtEpochMillis)
            assertEquals(before.sourcePageUrl, after.sourcePageUrl)
            assertEquals("new.jpg", after.originalName)
            assertEquals("content://media/external/images/media/99", after.savedUri)
            assertEquals(0, dao.countSavedUriReferences(before.savedUri!!))
            val assignment = requireNotNull(catalog.assignmentById(before.id))
            assertTrue(assignment.isFavorite)
            assertEquals(folder.id, assignment.folderId)
            assertEquals(1, dao.all().size)
        } finally {
            database.close()
        }
    }

    @Test fun hiddenOrPrivateRowsCannotBeMovedByPublicMigration() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), HistoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = database.downloadDao()
            val uri = "content://media/external/downloads/77"
            val hidden = DownloadEntity(id = "hidden-id", providerId = "instagram",
                originalName = "secret.jpg", mediaType = MediaType.PHOTO,
                state = DownloadState.COMPLETED, savedUri = uri,
                sizeBytes = 42, createdAtEpochMillis = 12, hidden = true)
            dao.upsert(hidden)
            assertEquals(0, dao.moveSavedUriWhenCurrent(
                hidden.id, uri, "content://media/external/images/media/88", "new.jpg"))
            val private = hidden.copy(id = "private-id", hidden = false, isPrivate = true)
            dao.upsert(private)
            assertEquals(0, dao.moveSavedUriWhenCurrent(
                private.id, uri, "content://media/external/images/media/88", "new.jpg"))
            assertEquals(2, dao.countSavedUriReferences(uri))
            assertFalse(requireNotNull(dao.byId(private.id)).hidden)
        } finally {
            database.close()
        }
    }
}
