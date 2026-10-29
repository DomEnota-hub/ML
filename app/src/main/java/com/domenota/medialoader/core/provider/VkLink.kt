package com.domenota.medialoader.core.provider

import java.net.URI
import java.net.URLDecoder

data class VkLink(private val canonical: String) {
    fun canonicalUrl(): String = canonical
}

object VkLinkParser {
    fun parse(raw: String): VkLink? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (!isVkHost(host)) return null

        val path = uri.path.orEmpty()
        val direct = MEDIA_PATH.matchEntire(path)?.value
            ?: SEARCH_CLIP_PATH.matchEntire(path)?.groupValues?.get(1)
            ?: uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { parameter ->
                val (key, value) = parameter.split('=', limit = 2).let {
                    it.first() to it.getOrElse(1) { "" }
                }
                if (!key.equals("z", ignoreCase = true)) null
                else runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
                    ?.let { MEDIA_QUERY.find(it)?.value }
            }
        if (direct != null) return VkLink("https://$host/${direct.removePrefix("/").removeSuffix("/")}")

        if (!path.equals("/video_ext.php", ignoreCase = true)) return null
        val canonical = URI("https", null, host, -1, path, uri.query, null).toString()
        return VkLink(canonical)
    }

    private val MEDIA_PATH = Regex("/(?:video|clip)-?\\d+_\\d+/?", RegexOption.IGNORE_CASE)
    private val SEARCH_CLIP_PATH = Regex("/clips/search/.+/feed/(clip-?\\d+_\\d+)/?", RegexOption.IGNORE_CASE)
    private val MEDIA_QUERY = Regex("^(?:video|clip)-?\\d+_\\d+(?=/|$)", RegexOption.IGNORE_CASE)

    private fun isVkHost(host: String): Boolean =
        host == "vk.com" || host.endsWith(".vk.com") ||
            host == "vkvideo.ru" || host.endsWith(".vkvideo.ru") ||
            host == "vk.ru" || host.endsWith(".vk.ru")
}
