package com.domenota.medialoader.core.model

import kotlinx.coroutines.CompletableDeferred

/** In-memory unit of work for the download queue. [fileName] is already collision-free. */
data class DownloadTask(
    val id: String,
    val item: MediaItem,
    val fileName: String,
    val state: DownloadState = DownloadState.QUEUED,
    val bytesDownloaded: Long = 0,
    val savedUri: String? = null,
    val errorMessage: String? = null,
    /** This batch predecessor must finish its publication attempt before this task becomes visible. */
    val publishAfter: CompletableDeferred<Unit>? = null,
    /** Completed by DownloadQueue after this task has finished or been skipped/cancelled. */
    val publishDone: CompletableDeferred<Unit>? = null,
)

enum class DownloadState { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }
