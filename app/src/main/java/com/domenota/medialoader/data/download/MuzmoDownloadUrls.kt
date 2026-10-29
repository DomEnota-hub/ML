package com.domenota.medialoader.data.download

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Muzmo-only navigation and MP3 gates; never accept an arbitrary browser redirect. */
object MuzmoDownloadUrls {
    const val ORIGIN = "https://rmr.muzmo.cc"
    private const val HOST = "rmr.muzmo.cc"
    private val MUSIC_PATH = Regex("^/get/music/[0-9]{8}/[^/]+\\.mp3$", RegexOption.IGNORE_CASE)

    fun searchUrl(query: String): String =
        "$ORIGIN/search?q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())

    fun isSearchPage(url: String): Boolean = safe(url)?.let {
        it.host.equals(HOST, true) && it.path == "/search"
    } ?: false

    fun isInfoPage(url: String): Boolean = safe(url)?.let {
        it.host.equals(HOST, true) && it.path == "/info" &&
            it.rawQuery?.split('&')?.any { param ->
                param.matches(Regex("id=[0-9]{1,18}"))
            } == true
    } ?: false

    fun isSitePage(url: String): Boolean = isSearchPage(url) || isInfoPage(url)

    fun isMp3(url: String): Boolean = safe(url)?.let {
        it.host.equals(HOST, true) && MUSIC_PATH.matches(it.path)
    } ?: false

    /** Muzmo redirects MP3 requests to its observed audio host, mzmdl.com. */
    fun isTrustedRedirect(url: String): Boolean = safe(url)?.let {
        (it.host.equals(HOST, true) || it.host.equals("mzmdl.com", true)) &&
            MUSIC_PATH.matches(it.path)
    } ?: false

    private fun safe(raw: String): URI? = runCatching {
        val uri = URI(raw)
        if (!uri.scheme.equals("https", true) || uri.host == null ||
            uri.port !in listOf(-1, 443) || uri.userInfo != null ||
            uri.fragment != null || uri.path != uri.normalize().path ||
            uri.path.split('/').any { it == "." || it == ".." }
        ) null else uri
    }.getOrNull()

    fun normalizedHost(url: String): String? = safe(url)?.host?.lowercase(Locale.ROOT)
}
