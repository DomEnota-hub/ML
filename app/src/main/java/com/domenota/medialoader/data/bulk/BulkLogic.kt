package com.domenota.medialoader.data.bulk

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.provider.InstagramLinkParser
import com.domenota.medialoader.core.provider.RutubeLinkParser
import com.domenota.medialoader.core.provider.VkLinkParser
import com.domenota.medialoader.core.provider.YouTubeLinkParser

data class BulkParsedLinks(val urls: List<String>, val unsupportedUrls: List<String>, val duplicates: Int) {
    val unsupported: Int get() = unsupportedUrls.size
}

object BulkLinks {
    const val MAX_BYTES = 1_048_576
    const val MAX_LINKS = 200
    private val urlPattern = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

    fun provider(url: String): String? = when {
        InstagramLinkParser.parse(url) != null -> "instagram"
        YouTubeLinkParser.parse(url) != null -> "youtube"
        VkLinkParser.parse(url) != null -> "vk"
        RutubeLinkParser.parse(url) != null -> "rutube"
        else -> null
    }

    fun parse(text: String): BulkParsedLinks {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "TXT превышает 1 МиБ" }
        val seen = linkedSetOf<String>()
        val unsupported = mutableListOf<String>()
        var duplicates = 0
        urlPattern.findAll(text.removePrefix("\uFEFF")).forEach { match ->
            val raw = match.value.trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?')
            val canonical = InstagramLinkParser.parse(raw)?.pageUrl()
                ?: YouTubeLinkParser.parse(raw)?.canonicalUrl()
                ?: VkLinkParser.parse(raw)?.canonicalUrl()
                ?: RutubeLinkParser.parse(raw)?.canonicalUrl()
            if (canonical == null) unsupported.add(raw)
            else if (!seen.add(canonical)) duplicates++
            require(seen.size <= MAX_LINKS) { "В одном задании не больше 200 ссылок" }
        }
        return BulkParsedLinks(seen.toList(), unsupported, duplicates)
    }
}

data class BulkRules(
    val instagram: Boolean = true,
    val youtube: Boolean = true,
    val vk: Boolean = true,
    val rutube: Boolean = true,
    val photo: Boolean = true,
    val video: Boolean = true,
    val audio: Boolean = false,
    /** Null means best available, otherwise choose best quality not above this height. */
    val maxHeight: Int? = 1080,
    /** User-requested opt-out of the Instagram delay; rate-limit stop remains enabled. */
    val disableAnalysisPause: Boolean = false,
) {
    fun hasSelectedSources(): Boolean = instagram || youtube || vk || rutube
    fun hasSelectedTypes(): Boolean = photo || video || audio
    fun isUsable(): Boolean = hasSelectedSources() && hasSelectedTypes()

    fun accepts(provider: String): Boolean = when (provider) {
        "instagram" -> instagram
        "youtube" -> youtube
        "vk" -> vk
        "rutube" -> rutube
        else -> false
    }
}

data class BulkSelectionReport(
    val items: List<MediaItem>,
    val videosWithoutSuitableQuality: Int,
    val videosWithoutKnownQuality: Int,
)

/**
 * Instagram has no user-selectable resolution: always keep the best variant the
 * provider supplied, even when metadata does not disclose frame dimensions.
 * For YouTube/VK/RUTUBE the explicit resolution ceiling remains strict.
 */
object BulkSelection {
    private val pHeight = Regex("""(?i)(?<!\d)(\d{3,4})\s*p\b""")
    private val frameSize = Regex("""(?i)\b\d{3,4}\s*[x×]\s*(\d{3,4})\b""")

    fun knownHeight(label: String?): Int? {
        if (label.isNullOrBlank()) return null
        val number = pHeight.find(label)?.groupValues?.get(1)?.toIntOrNull()
            ?: frameSize.find(label)?.groupValues?.get(1)?.toIntOrNull()
        return number?.takeIf { it in 144..4320 }
    }

    private val instagramFrame = Regex("""(?i)\b(\d{3,5})\s*[x×]\s*(\d{3,5})\b""")

    private fun instagramPixels(label: String?): Long =
        label?.let(instagramFrame::find)?.let {
            (it.groupValues[1].toLongOrNull() ?: 0L) *
                (it.groupValues[2].toLongOrNull() ?: 0L)
        } ?: 0L

    fun select(items: List<MediaItem>, rules: BulkRules): List<MediaItem> =
        withReport(items, rules).items

    fun withReport(items: List<MediaItem>, rules: BulkRules): BulkSelectionReport {
        if (!rules.isUsable()) return BulkSelectionReport(emptyList(), 0, 0)
        val accepted = items.filter { rules.accepts(it.providerId) }
        val photos = if (rules.photo) accepted.filter { it.type == MediaType.PHOTO } else emptyList()
        val audio = if (rules.audio) accepted.filter { it.type == MediaType.AUDIO }
            .distinctBy { it.id } else emptyList()
        var skipped = 0
        var unverified = 0
        val videos = if (rules.video) accepted.filter { it.type == MediaType.VIDEO }
            .groupBy { if (it.providerId == "instagram") it.id
                else it.group ?: it.sourceGroupId ?: it.id }
            .values.mapNotNull { group ->
                val variants = group.map { it to knownHeight(it.qualityLabel) }
                if (group.first().providerId == "instagram") {
                    // One media object per post/carousel element. InstagramProvider
                    // already chooses the best CDN version available. Do not apply
                    // user-selected 480p/720p/1080p limits or reject unknown labels.
                    group.maxByOrNull { instagramPixels(it.qualityLabel) } ?: group.first()
                } else if (rules.maxHeight == null) {
                    variants.maxByOrNull { it.second ?: 0 }?.first
                } else {
                    val suitable = variants.filter { (_, height) ->
                        height != null && height <= rules.maxHeight
                    }
                    if (suitable.isNotEmpty()) suitable.maxByOrNull { it.second!! }?.first
                    else {
                        skipped++
                        if (variants.any { it.second == null }) unverified++
                        null
                    }
                }
            } else emptyList()
        return BulkSelectionReport(
            (photos + audio + videos).distinctBy { it.id },
            skipped,
            unverified,
        )
    }
}

/** Instagram CDN URLs expire; publication and carousel item IDs do not. */
object BulkIdentity {
    fun key(item: MediaItem): String = listOf(
        item.providerId,
        item.sourceGroupId.orEmpty(),
        item.id,
        item.type.name,
        if (item.providerId == "instagram") "" else item.formatSelector.orEmpty(),
    ).joinToString("|")
}

object BulkPacing {
    const val INSTAGRAM_INTERVAL_MS = 20_000L
    fun waitMillis(lastAttemptAt: Long, now: Long, disableAnalysisPause: Boolean = false): Long =
        if (disableAnalysisPause || lastAttemptAt <= 0L) 0L
        else (lastAttemptAt + INSTAGRAM_INTERVAL_MS - now).coerceAtLeast(0L)
}

object BulkCandidateSelection {
    fun setAll(candidates: List<BulkCandidate>, selected: Boolean): List<BulkCandidate> =
        candidates.map { it.copy(selected = selected) }
}

/** Stable group identifier: items from one source are one card in Download history. */
object BulkDownloadGroup {
    fun id(jobId: String, item: MediaItem): String = "$jobId:${item.sourcePageUrl ?: item.sourceGroupId ?: item.id}"
}
