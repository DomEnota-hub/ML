package com.domenota.medialoader.data.download

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import com.domenota.medialoader.core.storage.StorageNaming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MediaHeaders(val sizeBytes: Long?, val fileStem: String?)

/** The existing HEAD request also checks Content-Disposition for a readable source name. */
internal suspend fun mediaHeaders(url: String): MediaHeaders? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.setRequestProperty("Referer", "https://www.instagram.com/")
            if (connection.responseCode in 200..299) MediaHeaders(
                connection.contentLengthLong.takeIf { it > 0 },
                filenameFromDisposition(connection.getHeaderField("Content-Disposition")),
            ) else null
        } finally { connection.disconnect() }
    }.getOrNull()
}

/** RFC 5987 UTF-8 and ordinary quoted filenames; path components and CDN tokens are ignored. */
internal fun filenameFromDisposition(value: String?): String? {
    if (value == null) return null
    val encoded = Regex("(?i)filename\\*\\s*=\\s*UTF-8''([^;]+)").find(value)?.groupValues?.get(1)
    val plain = Regex("(?i)filename\\s*=\\s*(?:\"([^\"]+)\"|([^;]+))").find(value)
    val name = if (encoded != null) runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
        else plain?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.trim()
    val leaf = name ?: return null
    if ('/' in leaf || '\\' in leaf) return null
    return StorageNaming.meaningfulStem(leaf.substringBeforeLast('.'))
}
