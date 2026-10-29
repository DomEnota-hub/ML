package com.domenota.medialoader.data.instagramprofile

import java.net.URI

data class InstagramProfileLink(val username: String) {
    val url: String get() = "https://www.instagram.com/$username/"

    companion object {
        private val name = Regex("[A-Za-z0-9._]{1,30}")
        private val reserved = setOf("p", "reel", "reels", "tv", "stories", "accounts", "explore",
            "direct", "about", "api", "graphql", "challenge", "developer")

        fun parse(input: String): InstagramProfileLink? {
            val raw = input.trim()
            val candidate = when {
                raw.startsWith('@') -> raw.drop(1)
                !raw.contains("://") && !raw.contains('/') -> raw
                else -> {
                    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
                    if (uri.scheme?.lowercase() != "https" ||
                        uri.host?.lowercase()?.removePrefix("www.") != "instagram.com" ||
                        uri.userInfo != null || uri.port !in listOf(-1, 443)) return null
                    val segments = uri.path.trim('/').split('/')
                    if (segments.size != 1) return null
                    segments.single()
                }
            }
            return candidate.takeIf { name.matches(it) && it.lowercase() !in reserved &&
                !it.startsWith('.') && !it.endsWith('.') && !it.contains("..") }
                ?.lowercase()?.let(::InstagramProfileLink)
        }
    }
}

data class InstagramProfileInfo(
    val id: String, val username: String, val name: String, val avatarUrl: String?, val private: Boolean,
)

data class InstagramProfilePost(
    val code: String, val epochSecond: Long, val video: Boolean,
    val previewUrl: String?, val mediaCount: Int, val reel: Boolean = false,
) {
    val pageUrl: String get() = "https://www.instagram.com/${if (reel) "reel" else "p"}/$code/"
}

data class InstagramProfilePage(val posts: List<InstagramProfilePost>, val cursor: String?)

class InstagramProfileFormatException(message: String) : Exception(message)
