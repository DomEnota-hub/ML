package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.logging.AppLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Authorized Instagram endpoint. The endpoint URL, the app id and request headers live only here,
 * so the whole client can be replaced without touching InstagramProvider.
 */
class InstagramApiClient(
    private val transport: suspend (url: String, cookies: String) -> String = ::fetchInstagramApi,
) {
    /** Raw JSON of /api/v1/media/{id}/info/ (posts, Reels, carousels and Stories share this shape). */
    suspend fun mediaInfo(mediaId: String, cookies: String): String =
        transport("https://www.instagram.com/api/v1/media/$mediaId/info/", cookies)
}

private const val INSTAGRAM_WEB_APP_ID = "936619743392459"
private const val API_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

internal object InstagramApiFailure {
    fun geoBlockedPublication(status: Int, body: String): ProviderException? {
        if (status != 400) return null
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        val message = parsed?.optString("message")
        if (!message.equals("geoblock_required", ignoreCase = true)) return null
        val description = if (parsed?.optString("description").orEmpty().contains("sensitive content", ignoreCase = true))
            "Instagram ограничил доступ для этого аккаунта. Проверьте настройки чувствительного контента и возраст в Instagram."
        else "Публикация недоступна для этого аккаунта или региона."
        return ProviderException(ProviderException.Reason.UNSUPPORTED, description, "instagram")
    }

    fun isRateLimited(status: Int, body: String): Boolean = status == 429 ||
        status == 400 && Regex("\\\"spam\\\"\\s*:\\s*true", RegexOption.IGNORE_CASE)
            .containsMatchIn(body)
}

internal suspend fun fetchInstagramApi(url: String, cookies: String): String = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 12000
        connection.readTimeout = 12000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", API_USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cookie", cookies)
        connection.setRequestProperty("X-IG-App-ID", INSTAGRAM_WEB_APP_ID)
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        Regex("csrftoken=([^;]+)").find(cookies)?.groupValues?.get(1)
            ?.let { connection.setRequestProperty("X-CSRFToken", it) }
        val status = connection.responseCode
        val detail = if (status !in 200..299)
            runCatching { connection.errorStream?.bufferedReader()?.use { reader ->
                val buffer = CharArray(64_000)
                var count = 0
                while (count < buffer.size) {
                    val read = reader.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                String(buffer, 0, count)
            } }
                .getOrNull().orEmpty().replace(Regex("[\\r\\n\\t]+"), " ") else ""
        if (status !in 200..299) {
            AppLog.i("InstagramProfile", "API feed HTTP $status · response=${detail.take(600)}")
        }
        InstagramApiFailure.geoBlockedPublication(status, detail)?.let { throw it }
        when {
            status in 200..299 -> connection.inputStream.bufferedReader().use { it.readText().take(6_000_000) }
            InstagramApiFailure.isRateLimited(status, detail) || status == 403 ||
                (status in 300..399 && connection.getHeaderField("Location")?.let {
                    it.contains("/challenge/", true) || it.contains("/checkpoint/", true)
                } == true) -> throw ProviderException(
                ProviderException.Reason.RATE_LIMITED,
                "Instagram ограничил запросы или требует проверку аккаунта.", "instagram",
            )
            status == 301 || status == 302 || status == 401 -> throw ProviderException(
                ProviderException.Reason.ACCESS_REQUIRED,
                "Сессия Instagram недействительна или нет доступа. Войдите снова.", "instagram",
            )
            status == 404 -> throw ProviderException(
                ProviderException.Reason.UNSUPPORTED,
                "Публикация не найдена или недоступна для этого аккаунта.", "instagram",
            )
            else -> throw ProviderException(
                ProviderException.Reason.TEMPORARY_FAILURE,
                "Instagram временно не отвечает (HTTP $status).", "instagram",
            )
        }
    } finally {
        connection.disconnect()
    }
}
