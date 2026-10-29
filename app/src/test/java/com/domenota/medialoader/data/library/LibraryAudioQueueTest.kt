package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import org.junit.Assert.*
import org.junit.Test

class LibraryAudioQueueTest {
    @Test fun playlistOrderSurvivesMixedSourcesAndRejectsPrivateOrUnfinishedMembers() {
        fun track(id: String) = DownloadEntity(id, "test", id, MediaType.AUDIO, DownloadState.COMPLETED, "content://media/$id", null, 0)
        val rows = listOf(track("download"), track("phone:audio:1"), track("private").copy(isPrivate = true),
            track("pending").copy(state = DownloadState.QUEUED), track("photo").copy(mediaType = MediaType.PHOTO))
        assertEquals(listOf("phone:audio:1", "download"), LibraryAudioQueue.resolve(
            listOf("phone:audio:1", "private", "missing", "download", "photo", "pending"), rows, false).map { it.id })
        assertEquals(listOf("private"), LibraryAudioQueue.resolve(listOf("private", "download"), rows, true).map { it.id })
    }

    @Test fun internalRouteAliasesDoNotDuplicateTheSameFileInFallbackAudioQueue() {
        val app = DownloadEntity("app", "test", "Track", MediaType.AUDIO, DownloadState.COMPLETED, "content://media/42", null, 0)
        val alias = app.copy(id = "phone:audio:42")
        assertEquals(listOf("app"), LibraryAudioQueue.resolve(emptyList(), listOf(app, alias), false).map { it.id })
    }
}
