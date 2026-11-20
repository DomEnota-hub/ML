package com.domenota.medialoader.data

import androidx.room.Room
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class MusicDurationBackfillTest {
    private fun row(
        id: String, type: MediaType = MediaType.AUDIO,
        duration: Long? = null, isPrivate: Boolean = false,
        hidden: Boolean = false, state: DownloadState = DownloadState.COMPLETED
    ) = DownloadEntity(
        id, "ml-test", "$id.mp3", type, state,
        "content://media/external/audio/media/$id", 32000, 1700000000000L,
        durationMs = duration, isPrivate = isPrivate, hidden = hidden
    )

    @Test fun audioWithoutDurationIsEligibleButKnownAndProtectedAreNot() {
        assertTrue(MusicDurationBackfillPolicy.eligible(row("missing-audio")))
        assertTrue(MusicDurationBackfillPolicy.eligible(row("missing-video",type=MediaType.VIDEO)))
        assertTrue(MusicDurationBackfillPolicy.eligible(row("zero-audio",duration=0)))
        assertFalse(MusicDurationBackfillPolicy.eligible(row("known",duration=145000)))
        assertFalse(MusicDurationBackfillPolicy.eligible(row("private",isPrivate=true)))
        assertFalse(MusicDurationBackfillPolicy.eligible(row("hidden",hidden=true)))
        assertFalse(MusicDurationBackfillPolicy.eligible(row("pending",state=DownloadState.RUNNING)))
        assertFalse(MusicDurationBackfillPolicy.eligible(row("photo",type=MediaType.PHOTO)))
        assertFalse(MusicDurationBackfillPolicy.eligible(row("no-uri").copy(savedUri=null)))
    }

    @Test fun asyncResultNeverOverwritesMovedPrivateOrPreviouslyKnownFiles() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),HistoryDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao=db.downloadDao()
            val entry=row("track")
            dao.upsert(entry)
            assertEquals(1,dao.fillMissingDurationWhenCurrent(entry.id,requireNotNull(entry.savedUri),145000))
            assertEquals(145000L,requireNotNull(dao.byId(entry.id)).durationMs)
            assertEquals(0,dao.fillMissingDurationWhenCurrent(entry.id,requireNotNull(entry.savedUri),1))
            dao.upsert(entry.copy(durationMs=null,isPrivate=true))
            assertEquals(0,dao.fillMissingDurationWhenCurrent(entry.id,requireNotNull(entry.savedUri),145000))
            dao.upsert(entry.copy(durationMs=null,savedUri="content://media/external/audio/media/changed"))
            assertEquals(0,dao.fillMissingDurationWhenCurrent(entry.id,requireNotNull(entry.savedUri),145000))
            dao.upsert(entry.copy(durationMs=null,hidden=true))
            assertEquals(0,dao.fillMissingDurationWhenCurrent(entry.id,requireNotNull(entry.savedUri),145000))
            dao.upsert(entry.copy(durationMs=null))
            assertEquals(0,dao.fillMissingDurationWhenCurrent(entry.id,requireNotNull(entry.savedUri),0))
            assertEquals(1,dao.fillMissingDurationWhenCurrent(entry.id,requireNotNull(entry.savedUri),213000))
            assertEquals(213000L,requireNotNull(dao.byId(entry.id)).durationMs)
        } finally { db.close() }
    }
}
