package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType

interface MediaResolver {
    /** Selects a provider; the provider itself tries public access before any authorized fallback. */
    suspend fun resolve(url: String): List<MediaItem>
    fun canDownload(item: MediaItem): Boolean
}

class DefaultMediaResolver(private val providers: List<MediaProvider>) : MediaResolver {
    override fun canDownload(item: MediaItem): Boolean =
        providers.firstOrNull { it.id == item.providerId }?.safeDownloadUrl(item.downloadUrl) == true
    override suspend fun resolve(url: String): List<MediaItem> {
        val provider = providers.firstOrNull { it.supports(url) } ?: throw ProviderException(
            ProviderException.Reason.UNSUPPORTED,
            "Поддерживаются ссылки Instagram, YouTube, VK и RUTUBE.",
        )
        val items = provider.resolve(url)
        // A direct publication-level music asset is preferred over re-extracting audio from every video.
        val directAudioGroups = items
            .filter { it.providerId == InstagramProvider.ID && it.type == MediaType.AUDIO }
            .map { it.sourceGroupId ?: it.id }
            .toSet()
        // Unknown public pages may still contain audio; an explicit false from Instagram suppresses it.
        return items + items.filter {
            it.providerId == InstagramProvider.ID &&
                it.type == MediaType.VIDEO &&
                it.audioAvailable != false &&
                (it.sourceGroupId ?: it.id) !in directAudioGroups
        }.map(::audioTrackOf)
    }

    companion object {
        fun audioTrackOf(video: MediaItem) = MediaItem(
            id = "${video.id}_audio",
            providerId = video.providerId,
            type = MediaType.AUDIO,
            originalName = video.originalName.substringBeforeLast('.') + ".mp3",
            downloadUrl = video.downloadUrl,
            previewUrl = video.previewUrl,
            sourceGroupId = video.sourceGroupId,
            position = video.position,
        )
    }
}
