package com.domenota.medialoader.data.download

import com.domenota.medialoader.core.model.DownloadTask

/** Error whose message is safe to show to the user as is. */
class DownloadFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

data class DownloadResult(
    val savedUri: String,
    val sizeBytes: Long?,
    val fileName: String? = null,
)

/**
 * Turns one task into a saved file. The UI and providers know nothing about how this is done
 * (system DownloadManager, own HTTP client, audio extraction).
 * The call suspends until the file is stored; cancelling the coroutine must stop the transfer.
 */
interface DownloadEngine {
    suspend fun download(
        task: DownloadTask,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult
}
