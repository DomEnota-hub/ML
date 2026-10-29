package com.domenota.medialoader.core.provider

import java.net.URI

data class YouTubeLink(val videoId: String) {
    /** One video, never a playlist: list/index parameters are dropped on purpose. */
    fun canonicalUrl(): String = "https://www.youtube.com/watch?v=$videoId"
}

object YouTubeLinkParser {
    private val idPattern = Regex("[A-Za-z0-9_-]{11}")

    /** Accepts watch?v=, youtu.be/, shorts/, live/, embed/ on youtube.com, m.youtube.com and music.youtube.com. */
    fun parse(input: String): YouTubeLink? {
        val uri = try { URI(input.trim()) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val segments = uri.path?.trim('/')?.split('/')?.filter { it.isNotEmpty() }.orEmpty()

        val candidate = when (host) {
            "youtu.be" -> segments.firstOrNull()
            "youtube.com", "music.youtube.com" -> when (segments.firstOrNull()) {
                "watch" -> uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
                "shorts", "live", "embed", "v" -> segments.getOrNull(1)
                else -> null
            }
            else -> null
        }
        return candidate?.takeIf { idPattern.matches(it) }?.let(::YouTubeLink)
    }
}
