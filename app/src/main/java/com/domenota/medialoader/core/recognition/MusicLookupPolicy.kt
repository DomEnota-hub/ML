package com.domenota.medialoader.core.recognition

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType

/**
 * Shared music-lookup feature policy. Search never queues an excerpt; extraction
 * happens in temporary cache and only an explicit MP3 choice enters downloads.
 */
object MusicLookupPolicy {
    val supportedProviders: Set<String> = setOf("instagram", "youtube", "vk", "rutube")

    fun supports(providerId: String?): Boolean =
        providerId != null && providerId in supportedProviders

    fun preferredCandidate(items: List<MediaItem>): MediaItem? {
        val provider = items.firstOrNull()?.providerId ?: return null
        if (!supports(provider)) return null
        val sourceItems = items.filter { it.providerId == provider }
        return sourceItems.firstOrNull { it.type == MediaType.AUDIO }
            ?: sourceItems.firstOrNull {
                it.type == MediaType.VIDEO && it.audioAvailable != false
            }
    }
}

/** The UI must distinguish a missing input from a real acoustic no-match. */
internal enum class PreviewMusicStatus { MATCHED, AUDIO_UNAVAILABLE, NO_MATCH, EXTRACTION_FAILED, PROVIDER_FAILED }
internal data class PreviewMusicOutcome(val status: PreviewMusicStatus, val result: MusicRecognitionResult? = null) {
    init { require((status==PreviewMusicStatus.MATCHED)==(result!=null)) }
}
