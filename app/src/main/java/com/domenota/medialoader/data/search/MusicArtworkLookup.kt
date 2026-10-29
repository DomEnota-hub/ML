package com.domenota.medialoader.data.search

import com.domenota.medialoader.core.logging.AppLog
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Optional single-request artwork lookup for manual HitMoz song searches.
 *
 * Shazam acoustic recognition remains the first choice whenever it provided
 * artwork. A catalog lookup never delays song results or downloads, and is
 * applied only if artist AND song title exactly match after normalization.
 */
internal object MusicArtworkLookup {
    private const val MAX_RESPONSE_CHARS = 96_000
    private val successfulCovers = ConcurrentHashMap<String, String>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private val lookupSlots = Semaphore(2)

    fun lookup(artist: String, title: String): String? {
        if (artist.isBlank() || title.isBlank()) return null
        val key = normalized(artist) + "|" + normalized(title)
        successfulCovers[key]?.let { return it }
        if ((failedUntil[key] ?: 0L) > System.currentTimeMillis()) return null
        if (!lookupSlots.tryAcquire(2, TimeUnit.SECONDS)) return null
        return try {
            val term = URLEncoder.encode("$artist $title".take(160), StandardCharsets.UTF_8.name())
            val address = URL("https://itunes.apple.com/search?media=music&entity=song&limit=12&term=$term")
            val connection = address.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 4_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "MediaLoader/1.6")
                if (connection.responseCode != 200 || connection.contentLengthLong > MAX_RESPONSE_CHARS) {
                    return null
                }
                val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                    val chunk = CharArray(4096)
                    val output = StringBuilder()
                    while (true) {
                        val count = it.read(chunk)
                        if (count < 0) break
                        output.append(chunk, 0, count)
                        if (output.length > MAX_RESPONSE_CHARS) return null
                    }
                    output.toString()
                }
                val cover = parseExactArtwork(json, artist, title)
                if (cover != null) {
                    successfulCovers[key] = cover
                    failedUntil.remove(key)
                    if (successfulCovers.size > 100) successfulCovers.clear()
                } else {
                    if (failedUntil.size > 400) failedUntil.clear()
                    failedUntil[key] = System.currentTimeMillis() + 10 * 60_000L
                }
                cover
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            failedUntil[key] = System.currentTimeMillis() + 60_000L
            AppLog.verbose("MusicArtwork", "Catalog lookup unavailable · reason=${error.javaClass.simpleName}")
            null
        } finally {
            lookupSlots.release()
        }
    }

    internal fun parseExactArtwork(json: String, artist: String, title: String): String? {
        val list = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return null
        for (index in 0 until list.length().coerceAtMost(12)) {
            val row = list.optJSONObject(index) ?: continue
            if (!matches(artist, row.optString("artistName")) ||
                !matches(title, row.optString("trackName"))) continue
            val cover = row.optString("artworkUrl100").trim()
            if (isTrustedArtwork(cover)) return highResolutionArtwork(cover)
        }
        return null
    }

    /** Apple's /100x100bb.jpg preview is too small even for a history card. */
    internal fun highResolutionArtwork(url: String): String =
        url.replace(Regex("/\\d+x\\d+bb\\.(jpg|jpeg|png)$", RegexOption.IGNORE_CASE)) {
            "/600x600bb.${it.groupValues[1]}"
        }

    private fun normalized(value: String) = value.lowercase(Locale.ROOT)
        .replace("&", " and ")
        .filter(Char::isLetterOrDigit)

    private fun matches(left: String, right: String): Boolean =
        left.isNotBlank() && right.isNotBlank() && normalized(left) == normalized(right)

    internal fun isTrustedArtwork(address: String): Boolean = runCatching {
        val uri = URI(address)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null && uri.port in listOf(-1, 443) &&
            (host == "mzstatic.com" || host.endsWith(".mzstatic.com"))
    }.getOrDefault(false)
}
