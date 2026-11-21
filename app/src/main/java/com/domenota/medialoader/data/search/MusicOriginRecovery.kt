package com.domenota.medialoader.data.search

import android.os.SystemClock
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** No WebView/browser cookies, no user track query, no automatic following of external redirects.
 *  A permanent HTTPS redirect from the old trusted origin can establish continuity;
 *  search-only candidates need explicit confirmation after validating the site contract.
 */
internal object MusicOriginRecovery {
    private const val PROBE_QUERY = "Mozart"
    private const val COOLDOWN_MS = 10 * 60 * 1000L
    private const val MAX_BYTES = 512 * 1024
    private const val REQUEST_TIMEOUT = 3500
    private const val MAX_CANDIDATES = 6
    private val lock = Mutex()
    private val lastAttempt = mutableMapOf<MusicOriginProvider, Long>()

    data class Proposal internal constructor(
        val provider: MusicOriginProvider,
        val oldHost: String,
        val newHost: String,
        val confirmedRedirect: Boolean,
        private val issuedAt: Long = SystemClock.elapsedRealtime(),
    ) {
        fun isFresh() = SystemClock.elapsedRealtime() - issuedAt in 0L..300_000L
    }

    /** Called only after main-frame active search DNS/404/410 or potential new-brand redirect. */
    suspend fun investigate(provider: MusicOriginProvider, failedUrl: String): Proposal? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val oldHost = MusicOrigins.host(provider)
                if (MusicOriginPolicy.hostOf(provider, failedUrl) != oldHost ||
                    MusicOriginPolicy.httpsUri(failedUrl)?.path != "/search") return@withLock null
                val now = SystemClock.elapsedRealtime()
                if (lastAttempt[provider]?.let { now - it < COOLDOWN_MS } == true) return@withLock null
                lastAttempt[provider] = now
                resolve(provider, oldHost, ::fetch)?.let {
                    Proposal(provider, oldHost, it.host, it.confirmedRedirect)
                }
            }
        }

    internal data class Evidence(val host: String, val confirmedRedirect: Boolean)

    /** Dependency-injected transport for deterministic state-machine regression. */
    internal suspend fun resolve(
        provider: MusicOriginProvider,
        oldHost: String,
        probe: suspend (String) -> Response,
    ): Evidence? {
        val oldRoot = probe("https://$oldHost/")
        val oldSearch = probe(searchUrl(oldHost))
        currentCoroutineContext().ensureActive()
        val direct = listOf(oldRoot, oldSearch).firstNotNullOfOrNull {
            MusicOriginPolicy.permanentMigration(provider, oldHost, it.status, it.location)
        }
        if (direct != null && compatible(provider, direct, probe)) return Evidence(direct, true)

        // A live root or temporary/VPN/TLS error is not a move.
        val dnsMissing = oldRoot.dnsMissing && oldSearch.dnsMissing
        val gone = oldRoot.status in setOf(404, 410) && oldSearch.status in setOf(404, 410)
        if (!dnsMissing && !gone) return null
        val repeat = probe(searchUrl(oldHost))
        if (!(dnsMissing && repeat.dnsMissing) && !(gone && repeat.status in setOf(404, 410)))
            return null

        val checked = mutableSetOf(oldHost)
        val engines = listOf(
            "https://www.google.com/search?q=" + provider.brand,
            "https://yandex.ru/search/?text=" + provider.brand
        )
        for (engine in engines) {
            val page = probe(engine)
            if (page.status !in 200..299) continue
            // Bound each engine separately so the second can still recover from bad first-engine results.
            var attempts = 0
            for (host in candidates(provider, engine, page.body)) {
                currentCoroutineContext().ensureActive()
                if (!checked.add(host)) continue
                if (++attempts > 3) break
                if (compatible(provider, host, probe)) return Evidence(host, false)
            }
        }
        return null
    }

    suspend fun accept(proposal: Proposal): Boolean = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        MusicOrigins.accept(proposal)
    }

    private fun searchUrl(host: String) = "https://$host/search?q=$PROBE_QUERY"

    internal fun candidates(provider: MusicOriginProvider, engine: String, html: String): List<String> {
        val hosts = linkedSetOf<String>()
        val document = Jsoup.parse(html, engine)
        for (anchor in document.select("a[href]").take(150)) {
            var raw = anchor.absUrl("href")
            val uri = MusicOriginPolicy.httpsUri(raw) ?: continue
            // Google sometimes wraps a result in its own /url link. Never visit that redirect.
            if (uri.host == "www.google.com" && uri.path == "/url") {
                raw = uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
                    if (part.startsWith("q=") || part.startsWith("url="))
                        runCatching { URLDecoder.decode(part.substringAfter('='), "UTF-8") }.getOrNull()
                    else null
                } ?: continue
            }
            val host = MusicOriginPolicy.hostOf(provider, raw) ?: continue
            hosts.add(host)
            val base = host.split('.').takeLast(2).joinToString(".")
            hosts.add(provider.prefix + "." + base)
            if (hosts.size >= MAX_CANDIDATES) break
        }
        return hosts.take(MAX_CANDIDATES)
    }

    /** Search and real detail/MP3 path structure, without downloading audio or trusting arbitrary hosts. */
    private suspend fun compatible(provider: MusicOriginProvider, host: String, probe: suspend (String) -> Response): Boolean {
        if (MusicOriginPolicy.candidateHost(provider, host) != host) return false
        val search = probe(searchUrl(host))
        if (search.status !in 200..299) return false
        fun urls(body: String, base: String) = Jsoup.parse(body, base).select("a[href]")
            .mapNotNull { MusicOriginPolicy.httpsUri(it.absUrl("href")) }
            .filter { it.host.equals(host, true) }
        val links = urls(search.body, searchUrl(host))
        val audioPath = Regex("^/get/music/[0-9]{8}/[^/]+\\.mp3$", RegexOption.IGNORE_CASE)
        return when (provider) {
            MusicOriginProvider.HITMOZ -> links.filter { audioPath.matches(it.path) }.distinctBy { it.path }.size >= 2 &&
                links.any { it.path.matches(Regex("^/song/[0-9]+/?$")) }
            MusicOriginProvider.MUZMO -> {
                val details = links.filter { it.path == "/info" &&
                    it.rawQuery.orEmpty().split('&').any { p -> p.matches(Regex("id=[0-9]{1,18}")) } }
                    .distinctBy { it.toString() }
                if (details.size < 2) return false
                val detail = probe(details.first().toString())
                detail.status in 200..299 &&
                    urls(detail.body, details.first().toString()).any { audioPath.matches(it.path) }
            }
        }
    }

    internal data class Response(val status: Int = 0, val location: String? = null,
                                val body: String = "", val dnsMissing: Boolean = false)

    private suspend fun fetch(address: String): Response {
        currentCoroutineContext().ensureActive()
        val uri = MusicOriginPolicy.httpsUri(address) ?: return Response()
        return try {
            // Require every resolved address to be public. TLS hostname verification remains enabled;
            // Android networking performs its own resolution, so this is a preflight, not DNS pinning.
            val ips = InetAddress.getAllByName(uri.host)
            if (ips.isEmpty() || ips.any { !MusicOriginPolicy.publicAddress(it) }) return Response()
            val conn = URL(address).openConnection() as HttpsURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = REQUEST_TIMEOUT
                conn.readTimeout = REQUEST_TIMEOUT
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                conn.setRequestProperty("Cookie", "")
                val status = conn.responseCode
                val location = conn.getHeaderField("Location")?.let { runCatching { uri.resolve(it).toString() }.getOrNull() }
                if (status !in 200..299 || !conn.contentType.orEmpty().contains("html", true))
                    return Response(status, location)
                val body = conn.inputStream.use { stream ->
                    val buffer = ByteArray(8192)
                    val out = java.io.ByteArrayOutputStream()
                    while (out.size() < MAX_BYTES) {
                        currentCoroutineContext().ensureActive()
                        val read = stream.read(buffer, 0, minOf(buffer.size, MAX_BYTES - out.size()))
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                    out.toString("UTF-8")
                }
                Response(status, location, body)
            } finally { conn.disconnect() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: UnknownHostException) {
            Response(dnsMissing = true)
        } catch (_: IOException) {
            Response()
        } catch (_: SecurityException) {
            Response()
        }
    }
}
