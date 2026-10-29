package com.domenota.medialoader.data.diagnostics

import android.content.Context
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.diagnostics.DiagnosticCheckSpec
import com.domenota.medialoader.core.diagnostics.DiagnosticComponent
import com.domenota.medialoader.core.diagnostics.DiagnosticImpact
import com.domenota.medialoader.core.diagnostics.DiagnosticOutcome
import com.domenota.medialoader.core.diagnostics.DiagnosticProbe
import com.domenota.medialoader.core.diagnostics.DiagnosticScope
import com.domenota.medialoader.core.diagnostics.DiagnosticSource
import com.domenota.medialoader.core.diagnostics.InstagramAccountProbeRoute
import com.domenota.medialoader.core.diagnostics.SessionProbeRules
import com.domenota.medialoader.data.MediaRepository
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/** Read-only account probes. They never replace or clear the user's saved session. */
object ActiveSessionDiagnosticProbes {
    private data class AccountResponse(val code: Int, val body: String,
        val redirectHost: String?, val redirectPath: String?, val contentType: String?)
    private val accountSources = listOf(DiagnosticSource.INSTAGRAM,
        DiagnosticSource.YOUTUBE, DiagnosticSource.VK, DiagnosticSource.RUTUBE)

    fun probes(context: Context, repository: MediaRepository): List<DiagnosticProbe> =
        accountSources.map { source ->
            object : DiagnosticProbe {
                override val spec = DiagnosticCheckSpec("source.${source.id}.session",
                    DiagnosticComponent.SOURCES, DiagnosticImpact.OPTIONAL,
                    DiagnosticScope.FULL, timeoutMillis = 12_000, maxAgeMillis = 2 * 60_000)

                override suspend fun check(): DiagnosticOutcome {
                    val account = accountPage(source)
                    val cookies = if (source == DiagnosticSource.INSTAGRAM)
                        repository.sessions.cookies() else cookieHeader(context, source, account.host)
                    if (cookies.isNullOrBlank()) return SessionProbeRules.notStored()
                    if (source == DiagnosticSource.RUTUBE) return DiagnosticOutcome.unavailable(
                        "Проверочный адрес RUTUBE возвращает HTTP 404. Вход подтверждается в браузере; загрузка проверяется отдельно.",
                        "SESSION_PROBE_UNAVAILABLE")
                    val active = AtomicReference<HttpURLConnection?>()
                    return try {
                        val response = BoundedBlockingCall.run(onCancel = {
                            active.getAndSet(null)?.disconnect()
                        }) {
                            fun fetch(address: URI): AccountResponse {
                                val connection = (URL(address.toString()).openConnection() as HttpURLConnection).apply {
                                    instanceFollowRedirects = false
                                    connectTimeout = 4_000
                                    readTimeout = 4_000
                                    useCaches = false
                                    setRequestProperty("Cookie", cookies)
                                    setRequestProperty("Accept", "application/json,text/html")
                                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
                                    if (source == DiagnosticSource.INSTAGRAM) {
                                        setRequestProperty("X-IG-App-ID", "936619743392459")
                                        setRequestProperty("X-Requested-With", "XMLHttpRequest")
                                        Regex("(?:^|;)\\s*csrftoken=([^;]+)").find(cookies)
                                            ?.groupValues?.get(1)?.let { token ->
                                                setRequestProperty("X-CSRFToken", token)
                                            }
                                    }
                                }
                                active.set(connection)
                                return try {
                                    val code = connection.responseCode
                                    val redirect = runCatching {
                                        URI(connection.getHeaderField("Location"))
                                    }.getOrNull()
                                    val stream = when (code) {
                                        200 -> connection.inputStream
                                        400 -> connection.errorStream
                                        else -> null
                                    }
                                    val body = stream?.bufferedReader()?.use { reader ->
                                        val buffer = CharArray(8_192)
                                        val output = StringBuilder()
                                        val limit = if (code == 200) 128_000 else 16_384
                                        while (output.length < limit) {
                                            val count = reader.read(buffer, 0,
                                                minOf(buffer.size, limit - output.length))
                                            if (count <= 0) break
                                            output.append(buffer, 0, count)
                                        }
                                        output.toString()
                                    }.orEmpty()
                                    AccountResponse(code, body, redirect?.host, redirect?.path,
                                        connection.contentType?.substringBefore(';')?.lowercase())
                                } finally { active.set(null); connection.disconnect() }
                            }
                            val first = fetch(account)
                            val fallback = if (source == DiagnosticSource.INSTAGRAM)
                                InstagramAccountProbeRoute.fallbackAfter(first.code, first.body) else null
                            if (fallback == null) first else {
                                AppLog.i("Diagnostics", "Instagram account probe · primary HTTP ${first.code} · trying plain route")
                                fetch(URI(fallback))
                            }
                        }
                        SessionProbeRules.classify(source, response.code, response.body,
                            response.redirectHost, response.contentType, response.redirectPath).also { outcome ->
                            AppLog.i("Diagnostics", "Session probe · source=${source.id} · " +
                                "status=${response.code} · mime=${response.contentType ?: "unknown"} · " +
                                "reason=${outcome.reasonCode ?: "none"}")
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        val kind = when (error) {
                            is java.net.SocketTimeoutException -> "TIMEOUT"
                            is java.net.UnknownHostException -> "DNS"
                            is javax.net.ssl.SSLException -> "TLS"
                            else -> "REQUEST_FAILED"
                        }
                        AppLog.i("Diagnostics", "Session probe · source=${source.id} · reason=SESSION_$kind")
                        DiagnosticOutcome.unavailable("Проверка профиля не завершилась ($kind). Сессия не изменена.",
                            "SESSION_$kind")
                    }
                }
            }
        }

    private fun accountPage(source: DiagnosticSource): URI = URI(when (source) {
        DiagnosticSource.INSTAGRAM -> InstagramAccountProbeRoute.PRIMARY
        DiagnosticSource.YOUTUBE -> "https://www.youtube.com/account"
        DiagnosticSource.VK -> "https://m.vk.com/settings"
        DiagnosticSource.RUTUBE -> "https://rutube.ru/api/accounts/profile/"
        else -> error("No account probe for this source")
    })

    private fun cookieHeader(context: Context, source: DiagnosticSource, host: String): String? {
        val name = when (source) {
            DiagnosticSource.YOUTUBE -> "youtube-cookies.txt"
            DiagnosticSource.VK -> "vk-cookies.txt"
            DiagnosticSource.RUTUBE -> "rutube-session.txt"
            else -> return null
        }
        val file = File(context.filesDir, name)
        if (!file.isFile || file.length() !in 1..2_000_000) return null
        return runCatching {
            file.useLines { lines ->
                lines.mapNotNull { raw ->
                    val line = if (raw.startsWith("#HttpOnly_")) raw.removePrefix("#HttpOnly_")
                        else raw.takeUnless { it.startsWith('#') } ?: return@mapNotNull null
                    val fields = line.split('\t')
                    if (fields.size < 7) return@mapNotNull null
                    val domain = fields[0].removePrefix(".").lowercase()
                    val cookieName = fields[5]
                    val cookieValue = fields[6]
                    if ((host != domain && !host.endsWith(".$domain")) ||
                        !cookieName.matches(Regex("[A-Za-z0-9_-]{1,80}")) ||
                        cookieValue.any { it == '\r' || it == '\n' || it == ';' }) null
                    else "$cookieName=$cookieValue"
                }.take(60).joinToString("; ").take(8_192)
            }
        }.getOrNull()?.takeIf(String::isNotBlank)
    }
}
