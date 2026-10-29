package com.domenota.medialoader.data.diagnostics

import android.content.Context
import com.domenota.medialoader.core.diagnostics.DiagnosticCheckSpec
import com.domenota.medialoader.core.diagnostics.DiagnosticComponent
import com.domenota.medialoader.core.diagnostics.DiagnosticEvidence
import com.domenota.medialoader.core.diagnostics.DiagnosticImpact
import com.domenota.medialoader.core.diagnostics.DiagnosticOutcome
import com.domenota.medialoader.core.diagnostics.DiagnosticProbe
import com.domenota.medialoader.core.diagnostics.DiagnosticScope
import com.domenota.medialoader.core.diagnostics.DiagnosticSource
import com.domenota.medialoader.core.diagnostics.DiagnosticState
import com.domenota.medialoader.core.diagnostics.SourceDiagnosticRules
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.data.MediaRepository
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** An explicitly anonymous, bounded, document-only HTTP attempt. */
interface SourceDocumentTransport {
    suspend fun request(source: DiagnosticSource): SourceDocumentResponse
}

sealed class SourceDocumentResponse {
    data class Http(val code: Int) : SourceDocumentResponse()
    data class Unavailable(val issue: SourceDiagnosticRules.NetworkIssue) : SourceDocumentResponse()
}

/**
 * No HEAD (many sources reject it with 405) and no following unapproved redirects.
 * Only the six origins already present in MediaLoader are contacted; no song page,
 * media URL, user query or credentials are ever sent. Do not read response bodies.
 */
class AnonymousSourceDocumentTransport : SourceDocumentTransport {
    override suspend fun request(source: DiagnosticSource): SourceDocumentResponse =
        run {
            val endpoint = when (source) {
                DiagnosticSource.INSTAGRAM -> "https://www.instagram.com/"
                DiagnosticSource.YOUTUBE -> "https://m.youtube.com/"
                DiagnosticSource.VK -> "https://m.vk.com/"
                DiagnosticSource.RUTUBE -> "https://rutube.ru/"
                DiagnosticSource.PRIMARY_MUSIC -> "https://ru.hitmoz.org/"
                DiagnosticSource.RESERVE_MUSIC -> "https://rmr.muzmo.cc/"
            }
            // A process-wide Java CookieHandler may attach credentials to HTTP.
            // Refuse the anonymous request rather than altering global app settings.
            if (java.net.CookieHandler.getDefault() != null) {
                return@run SourceDocumentResponse.Unavailable(
                    SourceDiagnosticRules.NetworkIssue.COOKIE_HANDLER)
            }
            val active = AtomicReference<HttpURLConnection?>()
            BoundedBlockingCall.run(onCancel = { active.getAndSet(null)?.disconnect() }) {
                var connection: HttpURLConnection? = null
                try {
                    connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 4_000
                    readTimeout = 4_000
                    useCaches = false
                    // Never attach the user's WebView or backup login cookies.
                    setRequestProperty("Cookie", "")
                    setRequestProperty("Accept", "text/html,application/xhtml+xml")
                    setRequestProperty("User-Agent", "MediaLoader-Diagnostics/1.0")
                    }
                    active.set(connection)
                    SourceDocumentResponse.Http(connection.responseCode)
                } catch (_: UnknownHostException) {
                    SourceDocumentResponse.Unavailable(SourceDiagnosticRules.NetworkIssue.NAME_RESOLUTION)
                } catch (_: SocketTimeoutException) {
                    SourceDocumentResponse.Unavailable(SourceDiagnosticRules.NetworkIssue.TIMEOUT)
                } catch (_: SSLException) {
                    SourceDocumentResponse.Unavailable(SourceDiagnosticRules.NetworkIssue.TLS)
                } catch (_: IOException) {
                    SourceDocumentResponse.Unavailable(SourceDiagnosticRules.NetworkIssue.OTHER)
                } catch (_: SecurityException) {
                    SourceDocumentResponse.Unavailable(SourceDiagnosticRules.NetworkIssue.OTHER)
                } finally {
                    active.set(null)
                    connection?.disconnect()
                }
            }
        }
}

/**
 * Stage 3 adapters. Construction is pure and does not send a request.
 * The coordinator skips NETWORK probes unless user explicitly authorizes
 * WITH_NETWORK mode; it never alters sessions or the downloading pipeline.
 */
object SourceDiagnosticProbes {
    fun probes(
        context: Context,
        repository: MediaRepository,
        transport: SourceDocumentTransport = AnonymousSourceDocumentTransport(),
    ): List<DiagnosticProbe> {
        return DiagnosticSource.entries.flatMap { source ->
            buildList {
                if (source.requiresAccountCheck) {
                    add(probe("source.${source.id}.auth") {
                        val present = when (source) {
                            DiagnosticSource.INSTAGRAM -> repository.isLoggedIn()
                            DiagnosticSource.YOUTUBE -> repository.hasYouTubeCookies
                            DiagnosticSource.VK -> repository.hasVkCookies
                            DiagnosticSource.RUTUBE -> repository.hasRutubeSession
                            else -> false
                        }
                        SourceDiagnosticRules.storedSession(present)
                    })
                }
                add(probe("source.${source.id}.history") {
                    val rows = repository.downloads.first().filter { it.providerId == source.id }
                    SourceDiagnosticRules.history(
                        completed = rows.count { it.state == DownloadState.COMPLETED },
                        failed = rows.count { it.state == DownloadState.FAILED },
                        cancelled = rows.count { it.state == DownloadState.CANCELLED },
                    )
                })
                add(probe("source.${source.id}.http",
                    scope = DiagnosticScope.NETWORK, timeoutMillis = 9_000L,
                    maxAgeMillis = 2 * 60_000L) {
                    when (val response = transport.request(source)) {
                        is SourceDocumentResponse.Http -> SourceDiagnosticRules.http(response.code)
                        is SourceDocumentResponse.Unavailable ->
                            SourceDiagnosticRules.transportFailed(response.issue)
                    }
                })
            }
        }
    }

    private fun probe(
        code: String,
        scope: DiagnosticScope = DiagnosticScope.LOCAL,
        timeoutMillis: Long = 15_000L,
        maxAgeMillis: Long = 5 * 60_000L,
        block: suspend () -> DiagnosticOutcome,
    ): DiagnosticProbe = object : DiagnosticProbe {
        override val spec = DiagnosticCheckSpec(
            code = code,
            component = DiagnosticComponent.SOURCES,
            impact = DiagnosticImpact.OPTIONAL,
            scope = scope,
            timeoutMillis = timeoutMillis,
            maxAgeMillis = maxAgeMillis,
        )
        override suspend fun check(): DiagnosticOutcome = block()
    }
}
