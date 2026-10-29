package com.domenota.medialoader.data.privatevault

import androidx.room.Room
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivateHistoryIsolationTest {
    @Test fun publicListsAndBulkCleanupNeverExposeOrRemovePrivateRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HistoryDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.downloadDao()
            fun row(id: String, isPrivate: Boolean = false, hidden: Boolean = false) = DownloadEntity(
                id, "test", "$id.mp4", MediaType.VIDEO, DownloadState.COMPLETED,
                "file:///$id.mp4", 4, 1, isPrivate = isPrivate, hidden = hidden)
            dao.upsert(row("public"))
            dao.upsert(row("trash", hidden = true))
            dao.upsert(row("private", isPrivate = true))
            dao.upsert(row("private-hidden", isPrivate = true, hidden = true))
            assertEquals(listOf("public"), dao.observeAll().first().map { it.id })
            assertEquals(listOf("trash"), dao.observeHidden().first().map { it.id })
            assertEquals(1, dao.observeHiddenCount().first())
            assertEquals(listOf("private"), dao.observePrivate().first().map { it.id })
            dao.restoreHidden()
            assertTrue(requireNotNull(dao.byId("private-hidden")).hidden)
            dao.deleteHidden("private-hidden")
            dao.deleteMissingHistoryRow("private")
            dao.clearHidden()
            dao.clearHistory()
            assertEquals(setOf("private", "private-hidden"), dao.all().map { it.id }.toSet())
            dao.setDuration("private", 2000)
            val retained = requireNotNull(dao.byId("private"))
            assertTrue(retained.isPrivate)
            assertEquals("file:///private.mp4", retained.savedUri)
            assertEquals(2000L, retained.durationMs)
        } finally { db.close() }
    }
}
