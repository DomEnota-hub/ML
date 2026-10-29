package com.domenota.medialoader.data.share

import com.domenota.medialoader.core.provider.ProviderException
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.data.bulk.BulkIdentity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal sealed interface AutomaticShareResolution<out T> {
    data class Ready<T>(val value: T) : AutomaticShareResolution<T>
    data class Retry(val error: Exception) : AutomaticShareResolution<Nothing>
}

internal suspend fun <T> resolveAutomaticShare(
    runAttemptCount: Int,
    resolve: suspend () -> T,
): AutomaticShareResolution<T> = try {
    AutomaticShareResolution.Ready(resolve())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    val temporary = error is UnknownHostException || error is SocketTimeoutException ||
        error is ConnectException || error is NoRouteToHostException || error is SocketException ||
        (error is ProviderException && error.reason == ProviderException.Reason.TEMPORARY_FAILURE)
    // WorkManager counts completed failed runs from zero: 0..4 = five total attempts.
    if (!temporary || runAttemptCount >= 4) throw error
    AutomaticShareResolution.Retry(error)
}

/** The WorkRequest UUID survives retries; a new user share gets a fresh namespace. */
internal fun automaticShareDownloadId(requestId: String, item: MediaItem): String =
    UUID.nameUUIDFromBytes("$requestId:${BulkIdentity.key(item)}".toByteArray(Charsets.UTF_8)).toString()
