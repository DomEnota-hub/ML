package com.domenota.medialoader.data.preview

import java.net.URI
import java.util.Locale

/** Restricts VK cover downloads to the image hosts observed in VK extraction results. */
internal object VkThumbnailPolicy {
    fun isAllowedUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
            (host == "userapi.com" || host.endsWith(".userapi.com"))
    }

    fun isImageContentType(value: String?): Boolean =
        value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.startsWith("image/") == true
}
