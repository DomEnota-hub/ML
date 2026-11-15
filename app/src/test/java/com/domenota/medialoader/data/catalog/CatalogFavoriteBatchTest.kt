package com.domenota.medialoader.data.catalog

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
@Config(sdk = [28])
class CatalogFavoriteBatchTest {
    @Test fun repeatedSingleTogglePreservesFileFolderAndHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HistoryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val catalog = DownloadCatalogRepository(db)
            val folder = catalog.createFolder("Video")
            val id = "video"
            db.downloadDao().upsert(DownloadEntity(id, "test", "video.mp4", MediaType.VIDEO,
                DownloadState.COMPLETED, "file:///video.mp4", 1L, 1L))
            catalog.setFolder(id, folder)
            val original = db.downloadDao().byId(id)
            catalog.toggleFavorite(id)
            assertTrue(db.catalogDao().assignmentById(id)!!.isFavorite)
            catalog.toggleFavorite(id)
            assertFalse(db.catalogDao().assignmentById(id)!!.isFavorite)
            assertEquals(folder, db.catalogDao().assignmentById(id)!!.folderId)
            assertEquals(original, db.downloadDao().byId(id))
            catalog.toggleFavorite(id)
            assertTrue(db.catalogDao().assignmentById(id)!!.isFavorite)
        } finally { db.close() }
    }

    @Test fun mixedFavoritesCanBeSetUniformlyWithoutInvertingExistingFavoritesOrDroppingFolders() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HistoryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val catalog = DownloadCatalogRepository(db)
            val folder = catalog.createFolder("Group")
            for (id in listOf("a", "b")) db.downloadDao().upsert(DownloadEntity(id, "test", "$id.jpg", MediaType.PHOTO,
                DownloadState.COMPLETED, "file:///$id.jpg", 1L, 1L))
            catalog.setFolder("a", folder)
            catalog.toggleFavorite("a")
            runFileBatch(listOf("a", "b")) { catalog.setFavorite(it, true) }
            assertTrue(db.catalogDao().assignmentById("a")!!.isFavorite)
            assertTrue(db.catalogDao().assignmentById("b")!!.isFavorite)
            runFileBatch(listOf("a", "b")) { catalog.setFavorite(it, false) }
            assertFalse(db.catalogDao().assignmentById("a")!!.isFavorite)
            assertEquals(folder, db.catalogDao().assignmentById("a")!!.folderId)
            assertNull(db.catalogDao().assignmentById("b"))
        } finally { db.close() }
    }
}
