package com.domenota.medialoader.core.provider

import java.net.URI

/** Kind of Instagram URL. The kind decides which public page is requested. */
enum class InstagramLinkType(val pathSegment: String) {
    POST("p"),
    REEL("reel"),
    TV("tv"),
    STORY("stories"),
}

/** [id] is the shortcode for POST/REEL/TV and the numeric media id for STORY. */
data class InstagramLink(val type: InstagramLinkType, val id: String) {
    val isStory: Boolean get() = type == InstagramLinkType.STORY

    /** Public page in the same form the user shared: /p/, /reel/ or /tv/. Not meaningful for stories. */
    fun pageUrl(): String = "https://www.instagram.com/${type.pathSegment}/$id/"

    /** Public embed page for the same URL kind. Not meaningful for stories. */
    fun embedUrl(): String = "https://www.instagram.com/${type.pathSegment}/$id/embed/captioned/"
}

object InstagramLinkParser {
    private val shortcodePattern = Regex("[A-Za-z0-9_-]{5,64}")
    private val storyIdPattern = Regex("\\d{5,25}")

    /**
     * Accepts https://instagram.com/{p|reel|tv}/CODE/, the same with a leading /username/,
     * and https://instagram.com/stories/USER/ID/. Other hosts and paths return null.
     */
    fun parse(input: String): InstagramLink? {
        val uri = try { URI(input.trim()) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removePrefix("www.")
        if ((scheme != "http" && scheme != "https") || host != "instagram.com") return null
        val path = uri.path?.trim('/')?.split('/') ?: return null

        if (path.size >= 3 && path[0] == "stories" && path[1] != "highlights") {
            val id = path[2]
            return if (storyIdPattern.matches(id)) InstagramLink(InstagramLinkType.STORY, id) else null
        }

        fun typeAt(index: Int): InstagramLinkType? = when (path.getOrNull(index)) {
            "p" -> InstagramLinkType.POST
            "reel" -> InstagramLinkType.REEL
            "tv" -> InstagramLinkType.TV
            else -> null
        }

        val at = when {
            typeAt(0) != null -> 0
            typeAt(1) != null -> 1
            else -> return null
        }
        val type = typeAt(at) ?: return null
        val code = path.getOrNull(at + 1) ?: return null
        return if (shortcodePattern.matches(code)) InstagramLink(type, code) else null
    }
}
