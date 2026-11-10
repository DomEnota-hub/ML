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
}
