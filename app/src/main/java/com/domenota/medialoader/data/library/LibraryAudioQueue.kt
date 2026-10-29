package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.storage.SavedFileStatus

internal object LibraryAudioQueue {
    fun resolve(ids: List<String>, rows: List<DownloadEntity>, private: Boolean,
        statuses: Map<String, SavedFileStatus> = emptyMap()): List<DownloadEntity> {
        val byId = rows.associateBy { it.id }
        val ordered = if (ids.isEmpty()) rows else ids.distinct().mapNotNull { byId[it] }
        return ordered.filter { it.mediaType == MediaType.AUDIO && it.isPrivate == private && !it.hidden &&
            it.state == DownloadState.COMPLETED && !it.savedUri.isNullOrBlank() &&
            statuses[it.id] != SavedFileStatus.MISSING && statuses[it.id] != SavedFileStatus.INACCESSIBLE }
            .distinctBy { it.savedUri }
    }
}
