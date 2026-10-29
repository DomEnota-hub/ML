package com.domenota.medialoader.data.download

import android.content.Context
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.model.DownloadTask
import com.domenota.medialoader.data.storage.StorageManager
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Direct MP3 downloads explicitly selected on HitMoz.
 * No ad domains, auto-follow redirects, HTML-as-MP3 or WebView download manager.
 */
object HitMozDownloadUrls {
    /** Correlates browser and HTTP requests without exporting song names or signed query values. */
    fun diagnosticAddress(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "invalid"
        val path = uri.path.orEmpty()
        val kind = when {
            path.startsWith("/get/music/") -> "music"
            path.startsWith("/search") -> "search"
            else -> "other"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "path=$kind · query=${!uri.rawQuery.isNullOrEmpty()} · urlId=$digest"
    }

    fun isTrustedPage(url: String): Boolean = try {
        val uri = URI(url)
        isSafeHttps(uri) &&
            uri.host.equals("ru.hitmoz.org", ignoreCase = true) &&
            (uri.path.startsWith("/search") || uri.path.startsWith("/song/"))
    } catch (_: Exception) {
        false
    }

    fun isMp3(url: String): Boolean = try {
        val uri = URI(url)
        isSafeHttps(uri) &&
            uri.host.equals("ru.hitmoz.org", ignoreCase = true) &&
            uri.path.startsWith("/get/music/") &&
            uri.path.endsWith(".mp3", ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    /**
     * Verified from on-device HitMoz HTTP 302 logs (2026-10-14): audio is also hosted
     * on numbered deliciousoranges / deliciouspeaches CDN subdomains.
     *
     * HitMoz paths use the /get/music/ prefix with an MP3 extension. CDN links can be signed,
     * so require a known numbered media host and non-document path. All responses
     * must still pass MIME, size, and MP3 file-signature verification before publishing.
     */
    fun isTrustedRedirect(url: String): Boolean = try {
        val uri = URI(url)
        val host = uri.host?.lowercase(java.util.Locale.ROOT).orEmpty()
        val hitmozHost = listOf("hitmoz.org", "hitmoz.com").any { domain ->
            host == domain || host.endsWith(".$domain")
        }
        val musicCdn =
            host == "statcore.hitmcdn.com" ||
                host.matches(Regex("cdn[0-9]{1,3}\\.deliciousoranges\\.com")) ||
                host.matches(Regex("(?:s|cdn)[0-9]{1,3}\\.deliciouspeaches\\.com"))
        // HitMoz's own site uses /get/music/*.mp3. Redirected CDN file links can
        // use signed/opaque paths, so restrict those by host and verify MP3 bytes.
        val hitmozMusicPath = uri.path.startsWith("/get/music/") &&
            uri.path.endsWith(".mp3", ignoreCase = true)
        val cdnFilePath = uri.path.isNotBlank() && uri.path != "/" &&
            !uri.path.endsWith("/", ignoreCase = true) &&
            !uri.path.endsWith(".html", ignoreCase = true) &&
            !uri.path.endsWith(".htm", ignoreCase = true) &&
            !uri.path.endsWith(".json", ignoreCase = true) &&
            !uri.path.startsWith("/ads/", ignoreCase = true) &&
            !uri.path.startsWith("/promo/", ignoreCase = true)
        isSafeHttps(uri) &&
            ((hitmozHost && hitmozMusicPath) || (musicCdn && cdnFilePath))
    } catch (_: Exception) {
        false
    }

    private fun isSafeHttps(uri: URI): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host != null && uri.port in listOf(-1, 443) && uri.userInfo == null &&
            uri.path == uri.normalize().path &&
            !uri.path.split('/').any { it == "." || it == ".." }
}

/** Identifies common denial pages without storing their HTML or session data. */
internal object HitMozResponseDiagnostics {
    fun errorCategory(body: String): String = when {
        body.isBlank() -> "empty"
        body.contains("cloudflare", ignoreCase = true) ||
            body.contains("cf-challenge", ignoreCase = true) -> "cloudflare"
        body.contains("captcha", ignoreCase = true) -> "captcha"
        body.contains("rate limit", ignoreCase = true) ||
            body.contains("too many requests", ignoreCase = true) -> "rate-limit"
        body.contains("access denied", ignoreCase = true) ||
            body.contains("доступ запрещ", ignoreCase = true) -> "access-denied"
        body.contains("vpn", ignoreCase = true) -> "vpn"
        else -> "other"
    }
}

class HitMozDownloadEngine(
    private val context: Context,
    private val storage: StorageManager,
) : DownloadEngine {
    private val cdnHealth = HitMozCdnHealth()
    override suspend fun download(
        task: DownloadTask,
        onProgress: suspend (Long, Long?) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (task.item.providerId != "hitmoz" || !HitMozDownloadUrls.isMp3(task.item.downloadUrl)) {
            AppLog.w("HitMoz", "Download blocked before connection · provider=${task.item.providerId}")
            throw DownloadFailure("Ссылка источника не является разрешённым MP3.")
        }
        AppLog.verbose("HitMoz", "MP3 task · id=${task.id} · ${HitMozDownloadUrls.diagnosticAddress(task.item.downloadUrl)}")

        var lastFailure: Throwable? = null
        val networkFailures = mutableSetOf<HitMozNetworkFailure.Kind>()
        var preferPlatformTransport = false
        for (attempt in 1..MAX_START_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            AppLog.verbose("HitMoz", "MP3 HTTP attempt $attempt/$MAX_START_ATTEMPTS")
            val temp = File.createTempFile("hitmoz-", ".part", context.cacheDir)
            try {
                val result = downloadOnce(task, temp, onProgress, preferPlatformTransport)
                if (attempt > 1) {
                    com.domenota.medialoader.core.logging.AppLog.i(
                        "HitMoz",
                        "MP3 request recovered on attempt " + attempt,
                    )
                }
                return@withContext result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (retryable: RetryableHitMozFailure) {
                lastFailure = retryable
                com.domenota.medialoader.core.logging.AppLog.w(
                    "HitMoz",
                    "MP3 start attempt " + attempt + "/" + MAX_START_ATTEMPTS +
                        " did not produce a file",
                    retryable,
                )
                if (attempt < MAX_START_ATTEMPTS) {
                    val serverDelay = retryable.retryAfterMs
                    if (serverDelay != null && serverDelay > MAX_RETRY_AFTER_MS) {
                        throw DownloadFailure("Сервис просит повторить попытку позднее. Не перегружаем сервер.")
                    }
                    delay(serverDelay ?: RETRY_DELAY_MS * attempt)
                }
            } catch (error: IOException) {
                lastFailure = error
                val reason = HitMozNetworkFailure.classify(error)
                networkFailures += reason
                AppLog.i("HitMoz", "MP3 attempt diagnostic · attempt=$attempt · reason=${reason.logName}")
                if (!preferPlatformTransport) {
                    preferPlatformTransport = true
                    AppLog.w("HitMoz", "Cronet network error · next retry uses Android HTTP transport")
                }
                com.domenota.medialoader.core.logging.AppLog.w(
                    "HitMoz",
                    "MP3 network attempt " + attempt + "/" + MAX_START_ATTEMPTS + " failed",
                    error,
                )
                if (attempt < MAX_START_ATTEMPTS) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } finally {
                temp.delete()
            }
        }

        throw DownloadFailure(
            HitMozNetworkFailure.failedMessage(networkFailures),
            lastFailure,
        )
    }

    private suspend fun downloadOnce(
        task: DownloadTask,
        temp: File,
        onProgress: suspend (Long, Long?) -> Unit,
        preferPlatformTransport: Boolean,
    ): DownloadResult {
        var current = URL(task.item.downloadUrl)
        var connection: HttpURLConnection? = null
        try {
            for (hop in 0..MAX_REDIRECTS) {
                // User-tested MP3 links work when pasted into a browser address bar.
                // Such a top-level navigation has no originating Referer; sending
                // search-page cookies and an audio-only Accept was different from that
                // successful flow. Keep the transport Chromium-backed, but make the
                // request look like an address-bar navigation.
                val alignedAgent = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                    .getString("hitmoz_browser_user_agent", null)
                    ?.takeIf { it.startsWith("Mozilla/5.0") && it.length <= 512 }
                    ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0.0.0 Mobile Safari/537.36"
                val startedAt = System.nanoTime()
                AppLog.verbose(
                    "HitMoz",
                    "MP3 request · id=${task.id} · hop=$hop · method=GET · host=${current.host.lowercase()} · ${HitMozDownloadUrls.diagnosticAddress(current.toString())} · " +
                        "requestProfile=browser-direct · cookiesSent=false · refererSent=false · alignedBrowserAgent=${alignedAgent != DEFAULT_HTTP_UA} · " +
                        "accept=navigation · languageHeader=true · rangeHeader=false",
                )
                val active = HitMozHttpTransport.open(context, current, preferPlatformTransport).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    setRequestProperty("User-Agent", alignedAgent)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                    setRequestProperty("Upgrade-Insecure-Requests", "1")
                    setRequestProperty("Sec-Fetch-Dest", "document")
                    setRequestProperty("Sec-Fetch-Mode", "navigate")
                    setRequestProperty("Sec-Fetch-Site", "none")
                    setRequestProperty("Sec-Fetch-User", "?1")
                }
                connection = active
                val code = active.responseCode
                AppLog.verbose(
                    "HitMoz",
                    "MP3 response · id=${task.id} · hop=$hop · status=$code · host=${current.host.lowercase()} · " +
                        "${HitMozDownloadUrls.diagnosticAddress(current.toString())} · " +
                        "mime=${active.contentType.orEmpty().substringBefore(';').take(40)} · " +
                        "length=${active.contentLengthLong} · elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} · " +
                        "redirect=${active.getHeaderField("Location") != null} · " +
                        "cfRay=${active.getHeaderField("CF-Ray") != null} · " +
                        "setCookie=${active.getHeaderField("Set-Cookie") != null} · " +
                        "retryAfter=${active.getHeaderField("Retry-After") != null}",
                )

                if (code >= 400 && AppLog.isExtended()) {
                    val sample = runCatching {
                        active.errorStream?.bufferedReader()?.use { reader ->
                            val buffer = CharArray(4096)
                            val count = reader.read(buffer)
                            if (count > 0) String(buffer, 0, count) else ""
                        }.orEmpty()
                    }.getOrDefault("")
                    AppLog.verbose(
                        "HitMoz",
                        "MP3 error page · id=${task.id} · category=${HitMozResponseDiagnostics.errorCategory(sample)} · " +
                            "sampleChars=${sample.length} · html=${active.contentType.orEmpty().contains("html", ignoreCase = true)}",
                    )
                }

                if (code in 300..399) {
                    val location = active.getHeaderField("Location")
                        ?: throw RetryableHitMozFailure(
                            "Сервис ответил перенаправлением без адреса (HTTP " + code + ").",
                        )
                    val next = URL(current, location)
                    val targetHost = next.host.lowercase()
                    val trusted = HitMozDownloadUrls.isTrustedRedirect(next.toString())
                    com.domenota.medialoader.core.logging.AppLog.i(
                        "HitMoz",
                        "MP3 redirect HTTP " + code + " · from=" + current.host.lowercase() +
                            " · to=" + targetHost + " · trusted=" + trusted + " · hop=" + (hop + 1),
                    )
                    if (!trusted) {
                        throw DownloadFailure(
                            "Источник перенаправляет MP3 на неподтверждённый адрес (" +
                                targetHost + "). Загрузка остановлена.",
                        )
                    }
                    if (cdnHealth.coolingDown(targetHost, System.currentTimeMillis())) {
                        AppLog.w("HitMoz", "CDN temporarily avoided · host=$targetHost · reason=repeated-network-failures")
                        throw DownloadFailure("Сервис повторно направил файл на сервер, который несколько раз не ответил. Попробуйте позже.")
                    }
                    if (hop == MAX_REDIRECTS) {
                        throw RetryableHitMozFailure("Слишком много перенаправлений при получении MP3.")
                    }
                    active.disconnect()
                    connection = null
                    current = next
                    continue
                }

                if (code == 403) {
                    // Retrying the same denied request five times cannot repair a
                    // missing browser session or a rejected user action.
                    throw DownloadFailure("Сервер отказал в доступе к ссылке загрузки (HTTP 403). Проверьте расширенный журнал.")
                }
                if (code == 408 || code == 425 || code == 429 ||
                    code in 500..599
                ) {
                    throw RetryableHitMozFailure(
                        "Сервис временно не начал загрузку (HTTP " + code + ").",
                        retryAfterMs = if (code == 429 || code == 503)
                            HttpRetryAfterPolicy.delayMs(active.getHeaderField("Retry-After"))
                        else null,
                    )
                }
                if (code !in 200..299) {
                    throw DownloadFailure("Сервис не отдал MP3 (HTTP " + code + ").")
                }

                val type = active.contentType?.lowercase().orEmpty()
                if (type.contains("text/html") || type.contains("application/json") ||
                    type.contains("text/plain") || type.contains("application/xml")
                ) {
                    throw RetryableHitMozFailure("Вместо MP3 сайт вернул страницу.")
                }

                val length = active.contentLengthLong.takeIf { it > 0 }
                if (length != null && length > MAX_MP3_BYTES) {
                    throw DownloadFailure("MP3 слишком большой для загрузки.")
                }
                if (length != null && length + SPACE_RESERVE > context.cacheDir.usableSpace) {
                    throw DownloadFailure("Недостаточно места для MP3.")
                }

                var read = 0L
                active.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            read += count
                            if (read > MAX_MP3_BYTES) {
                                throw DownloadFailure("Превышен максимальный размер MP3.")
                            }
                            if (context.cacheDir.usableSpace < SPACE_RESERVE) {
                                throw DownloadFailure("Недостаточно места.")
                            }
                            output.write(buffer, 0, count)
                            onProgress(read, length)
                        }
                    }
                }

                if (read == 0L || length != null && read != length) {
                    throw RetryableHitMozFailure("Загрузка MP3 не началась или оборвалась.")
                }
                if (!isMp3File(temp)) {
                    throw RetryableHitMozFailure("Сайт не вернул корректный MP3.")
                }

                cdnHealth.recovered(current.host)
                com.domenota.medialoader.core.logging.AppLog.i(
                    "HitMoz",
                    "MP3 stream verified · host=" + current.host.lowercase() + " · bytes=" + read,
                )
                task.publishAfter?.await()
                val published = storage.publish(temp, task.fileName, "audio/mpeg")
                return DownloadResult(published.uri.toString(), read, published.fileName)
            }
            throw RetryableHitMozFailure("Не удалось получить MP3 из-за перенаправлений.")
        } catch (error: IOException) {
            val reason = HitMozNetworkFailure.classify(error)
            if (HitMozNetworkFailure.isNetworkFailure(reason)) {
                cdnHealth.failed(current.host, System.currentTimeMillis())
            }
            AppLog.verbose(
                "HitMoz",
                "MP3 network failure · host=${current.host.lowercase()} · reason=${reason.logName} · category=${error.javaClass.simpleName}",
            )
            throw error
        } finally {
            connection?.disconnect()
        }
    }

    private fun isMp3File(file: File): Boolean = file.inputStream().use { input ->
        val header = ByteArray(4)
        if (input.read(header) < 4) return@use false
        (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() &&
            header[2] == '3'.code.toByte()) ||
            (header[0].toInt() and 0xFF == 0xFF &&
                header[1].toInt() and 0xE0 == 0xE0 &&
                header[1].toInt() and 0x06 != 0)
    }

    private class RetryableHitMozFailure(
        message: String,
        cause: Throwable? = null,
        val retryAfterMs: Long? = null,
    ) : IOException(message, cause)

    private companion object {
        const val DEFAULT_HTTP_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0.0.0 Mobile Safari/537.36"
        const val MAX_REDIRECTS = 5
        const val MAX_START_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 900L
        const val MAX_RETRY_AFTER_MS = 30_000L
        const val MAX_MP3_BYTES = 150L * 1024 * 1024
        const val SPACE_RESERVE = 16L * 1024 * 1024
    }
}
