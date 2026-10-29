package com.domenota.medialoader.data.search

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.domenota.medialoader.core.logging.AppLog
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale

/**
 * Music-search-only WebView filter.
 *
 * Two layers: conservative resource blocking of known advertising hosts and
 * cosmetic clean-up in the caller. Audio/playback and file downloads are never
 * intercepted here. No VPN, privileged API or third-party filter SDK required.
 *
 * AdblockAndroid 1.0 was evaluated for this debug experiment, but its advertised
 * artifact was not resolvable in GitHub Actions; do not leave an unusable dep.
 */
internal object MusicAdFilter {
    private val adHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "google-analytics.com",
        "yandexadexchange.net", "an.yandex.ru", "adfox.ru", "adriver.ru",
        "adnxs.com", "adform.net", "taboola.com", "outbrain.com",
        "criteo.com", "criteo.net", "rubiconproject.com",
        "pubmatic.com", "openx.net", "popads.net", "propellerads.com",
        "exoclick.com", "onclickads.net", "trafficjunky.net",
        "mgid.com", "rtbhouse.com", "bidvol.com",
    )
    // The song site's video player and genuine MP3/CDN endpoints must remain
    // reachable. Block ad paths only on unrelated third-party resources.
    private val mediaHosts = setOf(
        "hitmoz.org", "hitmoz.com", "deliciousoranges.com", "deliciouspeaches.com",
    )
    private val adPaths = listOf(
        "/ads/", "/adserver/", "/adservice/", "/advertising/",
        "/popunder/", "/popup/", "/clickunder/", "/prebid/",
    )

    fun initialize(@Suppress("UNUSED_PARAMETER") context: Context) {
        AppLog.i("MusicAds", "Built-in WebView network filter enabled")
    }

    fun setup(web: WebView) {
        // Ads should not be able to start their own full-screen videos.
        // A deliberate press on the actual music player continues to work.
        web.settings.mediaPlaybackRequiresUserGesture = true
    }

    fun onPageStarted(@Suppress("UNUSED_PARAMETER") web: WebView,
                      @Suppress("UNUSED_PARAMETER") url: String?) = Unit

    fun isBlockedHost(host: String): Boolean = matchesDomain(host, adHosts)

    fun shouldBlockResource(url: String, mainFrame: Boolean = false): Boolean {
        if (mainFrame) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (matchesDomain(host, mediaHosts)) return false
        if (isBlockedHost(host)) return true
        val path = uri.rawPath?.lowercase(Locale.ROOT).orEmpty()
        return adPaths.any(path::contains)
    }

    fun intercept(
        @Suppress("UNUSED_PARAMETER") web: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (!shouldBlockResource(request.url.toString(), request.isForMainFrame)) return null
        AppLog.verboseSampled("MusicWebView", "ad:${request.url.host.orEmpty()}", "Blocked advertising resource · host=${request.url.host.orEmpty()}")
        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }

    private fun matchesDomain(host: String, domains: Set<String>): Boolean =
        domains.any { domain -> host == domain || host.endsWith(".$domain") }
}
