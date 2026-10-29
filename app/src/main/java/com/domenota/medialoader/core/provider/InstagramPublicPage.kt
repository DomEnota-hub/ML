package com.domenota.medialoader.core.provider

import android.util.Log
import com.domenota.medialoader.BuildConfig
import java.net.HttpURLConnection
import java.io.IOException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Regular mobile-browser User-Agent: the public path must not depend on pretending to be a crawler. */
private const val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

internal suspend fun fetchInstagramPage(url: String): String = withContext(Dispatchers.IO) {
    var next = URL(url)
    repeat(5) {
        val connection = next.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            val status = connection.responseCode
            val target = connection.getHeaderField("Location")?.let { location ->
                runCatching { URL(next, location) }.getOrNull()
            }
            val body = if (status in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText().take(6_000_000) }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText().take(300) }.orEmpty()
            }
            if (BuildConfig.DEBUG) {
                val sample = body.take(300).replace(Regex("(?i)(sessionid|csrftoken)=([^\\s;\"']+)")) {
                    "${it.groupValues[1]}=[redacted]"
                }.replace('\n', ' ')
                Log.d("MediaLoaderPublic", "HTTP $status Location=${target?.host.orEmpty()}${target?.path.orEmpty()} body=$sample")
            }
            val safeRedirect = target?.let { redirect ->
                redirect.protocol == "https" && redirect.host.lowercase().let {
                    it == "instagram.com" || it.endsWith(".instagram.com")
                }
            } == true
            if (status == 429 || status == 403 ||
                (status in 300..399 && safeRedirect && target!!.path.let {
                    it.startsWith("/challenge") || it.startsWith("/checkpoint")
                })) throw ProviderException(
                ProviderException.Reason.RATE_LIMITED,
                "Instagram ограничил запросы или требует проверку аккаунта.", "instagram",
            )
            if (status in 300..399 && safeRedirect && target!!.path.startsWith("/accounts/login")) {
                throw ProviderException(ProviderException.Reason.ACCESS_REQUIRED, "Instagram требует вход", "instagram")
            }
            if (status == 401 || status == 403) throw ProviderException(
                ProviderException.Reason.ACCESS_REQUIRED, "Instagram требует вход", "instagram",
            )
            if (status in 300..399 && safeRedirect) {
                next = target!!
                return@repeat
            }
            if (status !in 200..299) throw IOException("HTTP $status")
            if (next.path.startsWith("/accounts/login")) throw ProviderException(
                ProviderException.Reason.ACCESS_REQUIRED, "Instagram требует вход", "instagram",
            )
            return@withContext body
        } finally {
            connection.disconnect()
        }
    }
    throw IOException("Too many redirects")
}
