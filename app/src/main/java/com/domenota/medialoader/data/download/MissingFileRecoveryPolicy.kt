package com.domenota.medialoader.data.download

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.provider.*
import com.domenota.medialoader.data.bulk.BulkIdentity

/** An absent carousel component is never inferred from title, size or a list position. */
internal object MissingFileRecoveryPolicy {
    fun eligible(row: DownloadEntity) = row.state == DownloadState.COMPLETED && !row.hidden && !row.isPrivate

    fun match(row: DownloadEntity, candidates: List<MediaItem>): MediaItem? {
        if (!eligible(row)) return null
        val exact = candidates.filter { it.providerId == row.providerId && it.type == row.mediaType &&
            row.bulkMediaKey != null && BulkIdentity.key(it) == row.bulkMediaKey }
        if (row.bulkMediaKey != null) return exact.singleOrNull()
        // Legacy rows have no component identity. Only a demonstrably single source is safe.
        if (row.groupId != null || candidates.size != 1) return null
        val item = candidates.single()
        if (item.providerId != row.providerId || item.type != row.mediaType) return null
        val original = publication(row.sourcePageUrl ?: row.sourceUrl ?: return null) ?: return null
        val refreshed = publication(item.sourcePageUrl ?: item.downloadUrl) ?: return null
        return item.takeIf { original == refreshed && row.formatSelector == item.formatSelector }
    }

    private fun publication(url: String): String? =
        InstagramLinkParser.parse(url)?.let { "instagram:${it.isStory}:${it.id}" }
            ?: YouTubeLinkParser.parse(url)?.let { "youtube:${it.videoId}" }
            ?: VkLinkParser.parse(url)?.canonicalUrl()
            ?: RutubeLinkParser.parse(url)?.canonicalUrl()
}
