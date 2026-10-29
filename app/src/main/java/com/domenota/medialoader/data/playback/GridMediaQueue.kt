package com.domenota.medialoader.data.playback

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.storage.SavedFileStatus

/** Resolves a visible-grid snapshot without expanding it into unrelated or private media. */
internal object GridMediaQueue {
    fun resolve(ids: List<String>, rows: List<DownloadEntity>, private: Boolean,
        statuses: Map<String, SavedFileStatus> = emptyMap()): List<DownloadEntity> {
        val byId = rows.associateBy { it.id }
        return ids.distinct().mapNotNull { byId[it] }.filter {
            it.isPrivate == private && !it.hidden && it.state == DownloadState.COMPLETED &&
                !it.savedUri.isNullOrBlank() && it.mediaType != MediaType.AUDIO &&
                statuses[it.id] != SavedFileStatus.MISSING && statuses[it.id] != SavedFileStatus.INACCESSIBLE
        }
    }
}
