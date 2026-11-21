package com.domenota.medialoader.core.model

import com.domenota.medialoader.core.provider.YouTubeFormats
import com.domenota.medialoader.core.provider.YouTubeLinkParser
import com.domenota.medialoader.core.provider.YouTubeProvider
import java.net.URI

/** Strict eligibility for deliberate single-video automatic submission. Manual parsing stays unchanged. */
object YouTubeAutoSelection {
    fun canonicalUrl(raw: String): String? {
        val uri = try { URI(raw.trim()) } catch (_: Exception) { return null }
        if (uri.userInfo != null || uri.port != -1) return null
        val keys = uri.query.orEmpty().split('&').map { it.substringBefore('=') }
        if (keys.any { it.equals("list", true) || it.equals("index", true) } ||
            keys.count { it == "v" } > 1) return null
        return YouTubeLinkParser.parse(raw)?.canonicalUrl()
    }

    fun singleVideo(raw: String, items: List<MediaItem>): MediaItem? {
        val canonical = canonicalUrl(raw) ?: return null
        val id = YouTubeLinkParser.parse(canonical)?.videoId ?: return null
        if (items.isEmpty() || items.any {
                it.providerId != YouTubeProvider.ID ||
                    YouTubeLinkParser.parse(it.downloadUrl)?.canonicalUrl() != canonical ||
                    (it.sourcePageUrl != null && YouTubeLinkParser.parse(it.sourcePageUrl)?.canonicalUrl() != canonical)
            }) return null
        val videos = items.filter { it.type == MediaType.VIDEO }
        if (items.any { it.type == MediaType.PHOTO } || videos.isEmpty() ||
            items.map { it.id }.distinct().size != items.size ||
            videos.any { it.group != YouTubeProvider.VIDEO_GROUP }) return null
        val chosen = videos.singleOrNull { it.preselected } ?: return null
        val height = Regex("^(\\d{2,5})p(?:\\d+)?$", RegexOption.IGNORE_CASE)
            .matchEntire(chosen.qualityLabel.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it > 0 } ?: return null
        return chosen.takeIf {
            it.id == "${id}_${height}p" && it.formatSelector == YouTubeFormats.videoSelector(height)
        }
    }
}
