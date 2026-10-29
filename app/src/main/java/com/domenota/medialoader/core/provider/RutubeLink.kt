package com.domenota.medialoader.core.provider

import java.net.URI

data class RutubeLink(private val canonical: String) {
    fun canonicalUrl(): String = canonical
}

object RutubeLinkParser {
    private val videoPath = Regex("^/(?:live/)?video(?:/private)?/[0-9a-fA-F]{32}/?$")
    private val embedPath = Regex("^/(?:play/)?embed/[0-9A-Za-z]+/?$")
    private val legacyEmbedPath = Regex("^/video/embed/\\d+/?$")

    fun parse(raw: String): RutubeLink? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.userInfo != null) return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host != "rutube.ru") return null

        val path = uri.path.orEmpty()
        if (!videoPath.matches(path) && !embedPath.matches(path) && !legacyEmbedPath.matches(path)) {
            return null
        }

        val canonical = runCatching {
            URI(scheme, null, host, uri.port, uri.path, uri.query, null).toString()
        }.getOrElse { raw.trim().substringBefore('#') }
        return RutubeLink(canonical)
    }
}
