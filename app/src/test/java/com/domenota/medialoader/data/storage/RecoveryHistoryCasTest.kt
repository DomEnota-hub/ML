package com.domenota.medialoader.data.storage

import androidx.room.Room
import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryHistoryCasTest {
    @Test fun exactCasUpdatesOnlyPhysicalFieldsAndCanReverseWithoutNewDownload() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HistoryDatabase::class.java).build()
        try {
            val dao = db.downloadDao()
            val row = DownloadEntity("stable", "qa", "old.jpg", MediaType.PHOTO, DownloadState.COMPLETED,
                "old", 100, 123456, sourcePageUrl = "https://example.invalid/source", groupId = "group", bulkMediaKey = "bulk", derivedFromId = "parent")
            dao.upsert(row)
            db.catalogDao().insertFolder(CatalogFolderEntity("album", "Album", "album", 1))
            val assignment = CatalogAssignmentEntity(row.id, "album", true)
            db.catalogDao().saveAssignment(assignment)
            assertEquals(0, dao.rebindSavedFileWhenCurrent(row.id, "old", "renamed.jpg", 100, "new", "new.jpg", 120))
            assertEquals(0, dao.rebindSavedFileWhenCurrent(row.id, "old", "old.jpg", 101, "new", "new.jpg", 120))
            assertEquals(0, dao.rebindSavedFileWhenCurrent("missing", "old", "old.jpg", 100, "new", "new.jpg", 120))
            assertEquals(row, dao.byId(row.id))
            assertEquals(1, dao.rebindSavedFileWhenCurrent(row.id, "old", "old.jpg", 100, "new", "new.jpg", 120))
            assertEquals(row.copy(savedUri = "new", originalName = "new.jpg", sizeBytes = 120), dao.byId(row.id))
            assertEquals(assignment, db.catalogDao().assignmentById(row.id))
            assertEquals(1, dao.all().size)
            assertEquals(1, dao.rebindSavedFileWhenCurrent(row.id, "new", "new.jpg", 120, "old", "old.jpg", 100))
            assertEquals(row, dao.byId(row.id))
        } finally { db.close() }
    }
    @Test fun privateHiddenAndActiveRowsCannotBeRebound() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HistoryDatabase::class.java).build()
        try {
            val row = DownloadEntity("stable", "qa", "old.jpg", MediaType.PHOTO, DownloadState.COMPLETED, "old", 100, 123456)
            for (blocked in listOf(row.copy(isPrivate = true), row.copy(hidden = true), row.copy(state = DownloadState.RUNNING))) {
                db.downloadDao().upsert(blocked)
                assertEquals(0, db.downloadDao().rebindSavedFileWhenCurrent(row.id, "old", "old.jpg", 100, "new", "new.jpg", 120))
                assertEquals(blocked, db.downloadDao().byId(row.id))
            }
        } finally { db.close() }
    }
    @Test fun restoreCommitPreservesOriginalMetadataLinksChronologyAndRejectsStaleSnapshot() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HistoryDatabase::class.java).build()
        try {
            val dao = db.downloadDao()
            val repository = com.domenota.medialoader.data.HistoryRepository(dao, db.catalogDao(), db)
            val original = DownloadEntity("stable", "instagram", "original.jpg", MediaType.PHOTO,
                DownloadState.COMPLETED, "content://old", 10, 123456,
                sourceUrl = "old-source", sourcePageUrl = "publication", groupId = "carousel",
                bulkMediaKey = "component-2", recognizedTitle = "title", derivedFromId = "parent")
            dao.upsert(original)
            db.catalogDao().insertFolder(CatalogFolderEntity("album", "Album", "album", 1))
            val assignment = CatalogAssignmentEntity(original.id, "album", true)
            db.catalogDao().saveAssignment(assignment)
            assertTrue(repository.restoreMissingFile(original, "content://new", "new.jpg", 20))
            val restored = original.copy(savedUri = "content://new", originalName = "new.jpg", sizeBytes = 20)
            assertEquals(restored, dao.byId(original.id))
            assertEquals(assignment, db.catalogDao().assignmentById(original.id))
            assertFalse(repository.restoreMissingFile(original, "content://duplicate", "duplicate.jpg", 30))
            assertEquals(restored, dao.byId(original.id))
            assertEquals(1, dao.all().size)
            // The nullable legacy size path must retain the same safeguards.
            val legacy = original.copy(id = "legacy", sizeBytes = null)
            dao.upsert(legacy)
            assertTrue(repository.restoreMissingFile(legacy, "content://legacy-new", "legacy.jpg", 9))
            assertEquals(legacy.createdAtEpochMillis, dao.byId(legacy.id)?.createdAtEpochMillis)
            val changed = legacy.copy(hidden = true)
            dao.upsert(changed)
            assertFalse(repository.restoreMissingFile(legacy, "content://wrong", "wrong.jpg", 9))
            assertEquals(changed, dao.byId(legacy.id))
        } finally { db.close() }
    }

    @Test fun restoredIdentityFolderFavoriteCollectionAndSortDateSurviveDatabaseReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "missing-file-recovery-${java.util.UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, HistoryDatabase::class.java, name).build()
        val original = DownloadEntity("old", "instagram", "original.jpg", MediaType.PHOTO,
            DownloadState.COMPLETED, "content://missing", 10, 100)
        val newer = original.copy(id = "newer", savedUri = "content://newer", createdAtEpochMillis = 200)
        val assignment = CatalogAssignmentEntity(original.id, "folder", true)
        val member = MediaCollectionMemberEntity("member", "collection", "{\"historyId\":\"old\"}", null, 1)
        try {
            db.downloadDao().upsert(original)
            db.downloadDao().upsert(newer)
            db.catalogDao().insertFolder(CatalogFolderEntity("folder", "Folder", "folder", 1))
            db.catalogDao().saveAssignment(assignment)
            db.organizationDao().insertCollection(MediaCollectionEntity("collection", "Collection", 1))
            db.organizationDao().insertMember(member)
            assertTrue(com.domenota.medialoader.data.HistoryRepository(db.downloadDao(), db.catalogDao(), db)
                .restoreMissingFile(original, "content://restored", "restored.jpg", 20))
            db.close()
            db = Room.databaseBuilder(context, HistoryDatabase::class.java, name).build()
            assertEquals(listOf("newer", "old"), db.downloadDao().all().map { it.id })
            assertEquals(original.copy(savedUri = "content://restored", originalName = "restored.jpg", sizeBytes = 20),
                db.downloadDao().byId(original.id))
            assertEquals(assignment, db.catalogDao().assignmentById(original.id))
            assertEquals(listOf(member), db.organizationDao().members("collection"))
        } finally { db.close(); context.deleteDatabase(name) }
    }

}
