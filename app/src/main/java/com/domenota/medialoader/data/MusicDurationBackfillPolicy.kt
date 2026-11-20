package com.domenota.medialoader.data

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType

/** Metadata recovery runs once for incomplete public download history, not per Compose row. */
internal object MusicDurationBackfillPolicy {
    fun eligible(row: DownloadEntity): Boolean =
        row.state == DownloadState.COMPLETED && !row.isPrivate && !row.hidden &&
            (row.mediaType == MediaType.AUDIO || row.mediaType == MediaType.VIDEO) &&
            (row.durationMs == null || row.durationMs <= 0L) &&
            !row.savedUri.isNullOrBlank()
}
