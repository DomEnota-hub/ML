package com.domenota.medialoader.data.download

import com.domenota.medialoader.core.model.DownloadTask

/** Error whose message is safe to show to the user as is. */
open class DownloadFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

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

/** Only thrown before public storage publication. Never classify a commit or disk failure as retryable. */
internal class RecoveryTransferFailure(message:String,cause:Throwable?=null,val refreshUrl:Boolean=false):DownloadFailure(message,cause)

data class RecoveryTransferProgress(val attempt:Int,val bytes:Long=0,val total:Long?=null,
    val phase:RecoveryTransferPhase=RecoveryTransferPhase.TRANSFER)
enum class RecoveryTransferPhase { TRANSFER, RETRY_WAIT, REFRESH_URL, VERIFY_SAVE }

internal object RecoveryTransferPolicy {
    const val MAX_ATTEMPTS=3
    fun networkInterrupted(error:java.io.IOException):Boolean =
        error is java.net.ProtocolException || error is java.net.SocketTimeoutException ||
        error is java.net.SocketException || error is java.io.EOFException ||
        error is java.net.UnknownHostException
}
