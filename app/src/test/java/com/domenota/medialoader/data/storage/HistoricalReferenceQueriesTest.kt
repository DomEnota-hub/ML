package com.domenota.medialoader.data.storage

import androidx.room.Room
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoricalReferenceQueriesTest {
    private val columns = listOf("savedUri", "privateSourceUri", "previewUrl",
        "sourceAudioArtworkUrl", "recognitionArtworkUrl", "sourceUrl", "sourcePageUrl")
    private val artwork = columns.subList(2, 5)
    private val old = "content://media/external/video/media/42"

    private fun database() = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), HistoryDatabase::class.java).build()

    private fun row(id: String = "row") = DownloadEntity(id, "synthetic", "sample.mp4",
        MediaType.VIDEO, DownloadState.COMPLETED, null, 256L, 123L)

    private suspend fun setField(db: HistoryDatabase, column: String, uri: String,
        hidden: Boolean = false, privateRow: Boolean = false) {
        require(column in columns)
        db.openHelper.writableDatabase.execSQL("DELETE FROM downloads")
        db.downloadDao().upsert(row().copy(hidden = hidden, isPrivate = privateRow))
        db.openHelper.writableDatabase.execSQL("UPDATE downloads SET $column=? WHERE id='row'", arrayOf(uri))
    }

    @Test fun everyLocatorFieldMatchesRawAndMediaDocumentAliases() = runBlocking {
        val db = database()
        try {
            for (column in columns) {
                setField(db, column, old)
                assertTrue(column, HistoricalReferenceQueries.hasLiveSource(db, setOf(old)))
                assertTrue(column, HistoricalReferenceQueries.hasLiveSource(db,
                    PublicMediaIdentity.uriKeys("content://com.android.providers.media.documents/document/video%3A42")))
                assertFalse(column, HistoricalReferenceQueries.hasLiveSource(db,
                    PublicMediaIdentity.uriKeys("content://media/external/video/media/43")))
            }
        } finally { db.close() }
    }

    @Test fun normalizedFileAndRawDownloadsDocumentArePhysicalAliasesInEveryField() = runBlocking {
        val db = database()
        try {
            // Lexical identities only. No file is created or opened.
            val normalized = PublicMediaIdentity.uriKeys("file:///storage/emulated/0/Movies/sample.mp4")
            val forms = listOf("file:///storage/emulated/0/Movies/temporary/../sample.mp4",
                "content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FMovies%2Fsample.mp4")
            for (column in columns) for (uri in forms) {
                setField(db, column, uri)
                assertTrue("$column $uri", HistoricalReferenceQueries.hasLiveSource(db, normalized))
            }
        } finally { db.close() }
    }

    @Test fun excludedIdAndPhysicalOnlyApplyWithoutHidingPrivateOrHiddenOwners() = runBlocking {
        val db = database()
        try {
            for (hidden in listOf(false, true)) for (privateRow in listOf(false, true)) {
                for (column in columns) {
                    setField(db, column, old, hidden, privateRow)
                    assertTrue(column, HistoricalReferenceQueries.hasLiveSource(db, setOf(old)))
                    assertEquals(column, column in columns.take(2),
                        HistoricalReferenceQueries.hasLiveSource(db, setOf(old), physicalOnly = true))
                    assertFalse(column, HistoricalReferenceQueries.hasLiveSource(db, setOf(old), exceptId = "row"))
                    assertTrue(column, HistoricalReferenceQueries.hasLiveSource(db, setOf(old), exceptId = "other"))
                }
            }
            // Excluding the moved row must not exclude another owner of the same source.
            db.downloadDao().upsert(row("second-owner").copy(savedUri = old, hidden = true, isPrivate = true))
            assertTrue(HistoricalReferenceQueries.hasLiveSource(db, setOf(old), exceptId = "row", physicalOnly = true))
        } finally { db.close() }
    }

    @Test fun artworkChecksAllThreeFieldsIncludingHiddenAndPrivateRowsButIsExact() = runBlocking {
        val db = database()
        try {
            for (column in columns) {
                setField(db, column, old, hidden = true, privateRow = true)
                assertEquals(column, column in artwork, HistoricalReferenceQueries.hasArtwork(db, old))
                assertFalse(HistoricalReferenceQueries.hasArtwork(db,
                    "content://com.android.providers.media.documents/document/video%3A42"))
            }
        } finally { db.close() }
    }

    @Test fun activeDownloadsChecksEveryKnownStateAndRefusesUnknownRawStates() = runBlocking {
        val db = database()
        try {
            assertFalse(HistoricalReferenceQueries.hasActiveDownloads(db))
            for (state in DownloadState.values()) {
                db.downloadDao().upsert(row().copy(state = state, hidden = true, isPrivate = true))
                assertEquals(state.name, state in setOf(DownloadState.QUEUED, DownloadState.RUNNING),
                    HistoricalReferenceQueries.hasActiveDownloads(db))
            }
            // Bypass the Room enum converter to simulate a future/corrupt state.
            for (raw in listOf("FUTURE_STATE", "", "completed")) {
                db.openHelper.writableDatabase.execSQL("UPDATE downloads SET state=? WHERE id='row'", arrayOf(raw))
                assertTrue(raw, HistoricalReferenceQueries.hasActiveDownloads(db))
            }
            db.openHelper.writableDatabase.execSQL("UPDATE downloads SET state='COMPLETED' WHERE id='row'")
            assertFalse(HistoricalReferenceQueries.hasActiveDownloads(db))
            // state is NOT NULL in the actual Room schema; SQL NULL cannot be
            // inserted through this schema. Unknown text covers fail-closed data.
        } finally { db.close() }
    }

    @Test fun newWriterAfterNegativeResultIsObservedByBothNextSafetyQueries() = runBlocking {
        val db = database()
        try {
            assertFalse(HistoricalReferenceQueries.hasLiveSource(db, setOf(old)))
            assertFalse(HistoricalReferenceQueries.hasArtwork(db, old))
            // A different dispatcher writes between boundaries. No transaction spans
            // the first negative result and the next fresh query.
            withContext(Dispatchers.IO) {
                db.downloadDao().upsert(row().copy(savedUri = old, previewUrl = old,
                    hidden = true, isPrivate = true))
            }
            assertTrue(HistoricalReferenceQueries.hasLiveSource(db, setOf(old)))
            assertTrue(HistoricalReferenceQueries.hasArtwork(db, old))
            db.openHelper.writableDatabase.execSQL("DELETE FROM downloads")
            assertFalse(HistoricalReferenceQueries.hasLiveSource(db, setOf(old)))
            assertFalse(HistoricalReferenceQueries.hasArtwork(db, old))
        } finally { db.close() }
    }
}
