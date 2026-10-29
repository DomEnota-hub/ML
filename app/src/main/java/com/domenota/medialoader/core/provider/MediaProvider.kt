package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.model.MediaItem

/** Each source owns its URL recognition, extraction and optional authentication. */
interface MediaProvider {
    val id: String
    fun supports(url: String): Boolean
    suspend fun resolve(url: String): List<MediaItem>
    fun safeDownloadUrl(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme == "https" && uri.host != null && uri.userInfo == null
    }.getOrDefault(false)
}

class ProviderException(
    val reason: Reason,
    message: String,
    val providerId: String? = null,
) : Exception(message) {
    enum class Reason { ACCESS_REQUIRED, UNSUPPORTED, TEMPORARY_FAILURE, RATE_LIMITED }
}
