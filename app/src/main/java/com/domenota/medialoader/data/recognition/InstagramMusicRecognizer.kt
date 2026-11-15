package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.provider.InstagramProvider
import com.domenota.medialoader.core.provider.YouTubeProvider
import com.domenota.medialoader.core.recognition.MusicRecognitionProvider
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.core.recognition.RecognitionSource
import kotlinx.coroutines.CancellationException
import com.domenota.medialoader.core.logging.AppLog

/**
 * Interactive lookup can use source metadata immediately. Downloaded tracks
 * request acoustic recognition first so Shazam can supply the album artwork.
 */
class InstagramMusicRecognizer(
    private val acousticProvider: MusicRecognitionProvider = ShazamRecognitionProvider(),
) {
    fun requiresPcm(item: MediaItem): Boolean =
        item.type == MediaType.AUDIO && sourceMetadata(item) == null

    fun sourceMetadata(item: MediaItem): MusicRecognitionResult? {
        if (item.type != MediaType.AUDIO ||
            item.providerId !in setOf(InstagramProvider.ID, YouTubeProvider.ID, "hitmoz", "muzmo")) return null

        val artist = item.audioArtist?.trim()?.takeIf { it.isNotBlank() }
        val title = item.audioTitle?.trim()?.takeIf { it.isNotBlank() }
        if (artist == null || title == null ||
            (item.providerId == InstagramProvider.ID && com.domenota.medialoader.core.provider.isGenericInstagramMusicTitle(title))) return null

        return MusicRecognitionResult(
            title = title,
            artist = artist,
            artworkUrl = item.audioArtworkUrl,
            source = RecognitionSource.SOURCE_METADATA,
        )
    }

    suspend fun recognize(
        item: MediaItem,
        preferAcoustic: Boolean = false,
        pcmLoader: suspend () -> ShortArray,
    ): MusicRecognitionResult? {
        if (item.type != MediaType.AUDIO) return null
        val metadata = sourceMetadata(item)
        if (!preferAcoustic && metadata != null) return metadata
        return try {
            acousticProvider.recognize(pcmLoader()) ?: metadata
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!preferAcoustic || metadata == null) throw error
            AppLog.i("Recognition", "Acoustic lookup unavailable; keeping source metadata · provider=${item.providerId}")
            metadata
        }
    }

}
