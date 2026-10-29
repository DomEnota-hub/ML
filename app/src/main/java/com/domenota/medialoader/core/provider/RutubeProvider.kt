package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.storage.StorageNaming
import kotlinx.coroutines.CancellationException

/** Public RUTUBE videos through yt-dlp, with an authenticated WebView session used only when needed. */
class RutubeProvider(private val extractor: StreamExtractor) : MediaProvider {
    override val id: String = ID

    override fun supports(url: String): Boolean = RutubeLinkParser.parse(url) != null

    override suspend fun resolve(url: String): List<MediaItem> {
        val link = RutubeLinkParser.parse(url) ?: throw ProviderException(
            ProviderException.Reason.UNSUPPORTED,
            "Нужна ссылка на видео RUTUBE.",
            ID,
        )
        val info = try {
            extractor.extract(link.canonicalUrl())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ExtractionException) {
            throw ProviderException(
                when (error.kind) {
                    ExtractionException.Kind.AUTH_REQUIRED -> ProviderException.Reason.ACCESS_REQUIRED
                    ExtractionException.Kind.UNAVAILABLE -> ProviderException.Reason.UNSUPPORTED
                    ExtractionException.Kind.FAILED -> ProviderException.Reason.TEMPORARY_FAILURE
                },
                error.message ?: "Не удалось получить данные видео RUTUBE.",
                ID,
            )
        } catch (_: Exception) {
            throw ProviderException(
                ProviderException.Reason.TEMPORARY_FAILURE,
                "Не удалось получить данные видео RUTUBE.",
                ID,
            )
        }
        return itemsOf(link.canonicalUrl(), info)
    }

    private fun itemsOf(url: String, info: StreamInfo): List<MediaItem> {
        val stem = StorageNaming.sanitizeStem(info.title) ?: info.id
        val audioFormats = info.formats.filter { it.hasAudio }
        val audioOnly = audioFormats.filter { !it.hasVideo }
        val audioSize = audioOnly.mapNotNull { it.sizeBytes }.maxOrNull()
            ?: audioFormats.mapNotNull { it.sizeBytes }.minOrNull()
        val videos = info.formats.filter { it.hasVideo }

        if (videos.isEmpty() && audioFormats.isEmpty()) throw ProviderException(
            ProviderException.Reason.UNSUPPORTED,
            "RUTUBE не вернул доступных форматов для этого видео.",
            ID,
        )

        val heights = videos.mapNotNull { it.height }.distinct().sortedDescending()
        val defaultHeight = heights.firstOrNull { it <= MAX_DEFAULT_HEIGHT } ?: heights.lastOrNull()
        val videoItems = if (heights.isNotEmpty()) {
            heights.map { height ->
                val atHeight = videos.filter { it.height == height }
                val representative = atHeight.filter { it.sizeBytes != null }.maxByOrNull { it.sizeBytes ?: 0L }
                val size = representative?.sizeBytes
                val fps = atHeight.mapNotNull { it.fps }.maxOrNull()?.takeIf { it > 30 }
                MediaItem(
                    id = "${info.id}_${height}p",
                    providerId = ID,
                    type = MediaType.VIDEO,
                    originalName = "${stem}_${height}p.mp4",
                    downloadUrl = url,
                    previewUrl = info.thumbnailUrl,
                    sizeBytes = size,
                    qualityLabel = "${height}p" + (fps?.let { " ${it}fps" } ?: ""),
                    formatSelector = videoSelector(height),
                    group = VIDEO_GROUP,
                    preselected = height == defaultHeight,
                )
            }
        } else if (videos.isNotEmpty()) {
            listOf(
                MediaItem(
                    id = "${info.id}_best",
                    providerId = ID,
                    type = MediaType.VIDEO,
                    originalName = "$stem.mp4",
                    downloadUrl = url,
                    previewUrl = info.thumbnailUrl,
                    sizeBytes = videos.mapNotNull { it.sizeBytes }.maxOrNull(),
                    qualityLabel = "Лучшее",
                    formatSelector = BEST_VIDEO_SELECTOR,
                    group = VIDEO_GROUP,
                    preselected = true,
                ),
            )
        } else {
            emptyList()
        }

        val audio = if (audioFormats.isNotEmpty()) {
            listOf(
                MediaItem(
                    id = "${info.id}_audio",
                    providerId = ID,
                    type = MediaType.AUDIO,
                    originalName = "$stem.mp3",
                    downloadUrl = url,
                    previewUrl = info.thumbnailUrl,
                    sizeBytes = audioSize,
                    qualityLabel = "MP3 · 192 kbps",
                    preselected = false,
                ),
            )
        } else {
            emptyList()
        }

        return videoItems + audio
    }

    companion object {
        const val ID = "rutube"
        const val VIDEO_GROUP = "rutube-video-quality"
        const val MAX_DEFAULT_HEIGHT = 1080
        const val BEST_VIDEO_SELECTOR = "bv*+ba/b"
        const val AUDIO_SELECTOR = "bestaudio/best"

        fun videoSelector(height: Int): String =
            "bv*[height=$height]+ba/b[height=$height]/best[height=$height]"
    }
}
