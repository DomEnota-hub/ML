package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.storage.StorageNaming
import kotlinx.coroutines.CancellationException

/**
 * YouTube through a [StreamExtractor]. It offers one item per video quality (mutually exclusive,
 * the best one up to 1080p preselected) and one audio-only item.
 */
class YouTubeProvider(private val extractor: StreamExtractor) : MediaProvider {
    override val id = ID

    override fun supports(url: String): Boolean = YouTubeLinkParser.parse(url) != null

    override suspend fun resolve(url: String): List<MediaItem> {
        val link = YouTubeLinkParser.parse(url) ?: throw ProviderException(
            ProviderException.Reason.UNSUPPORTED,
            "Нужна ссылка на видео YouTube.",
            ID,
        )
        val info = try {
            extractor.extract(link.canonicalUrl())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ExtractionException) {
            val authMessage = error.message.orEmpty().contains("требует вход в аккаунт", ignoreCase = true)
            throw ProviderException(
                when {
                    authMessage || error.kind == ExtractionException.Kind.AUTH_REQUIRED -> ProviderException.Reason.ACCESS_REQUIRED
                    error.kind == ExtractionException.Kind.UNAVAILABLE -> ProviderException.Reason.UNSUPPORTED
                    else -> ProviderException.Reason.TEMPORARY_FAILURE
                },
                error.message ?: "Не удалось получить данные видео.",
                ID,
            )
        } catch (_: Exception) {
            throw ProviderException(
                ProviderException.Reason.TEMPORARY_FAILURE,
                "Не удалось получить данные видео YouTube.",
                ID,
            )
        }
        return itemsOf(link.canonicalUrl(), info)
    }

    private fun itemsOf(url: String, info: StreamInfo): List<MediaItem> {
        val stem = StorageNaming.sanitizeStem(info.title) ?: info.id
        val audioOnly = info.formats.filter { it.hasAudio && !it.hasVideo }
        val hasAudio = info.formats.any { it.hasAudio }
        val audioSize = audioOnly.mapNotNull { it.sizeBytes }.maxOrNull()
        val videos = info.formats.filter { it.hasVideo && it.height != null }
        val heights = videos.mapNotNull { it.height }.distinct().sortedDescending()
        if (heights.isEmpty() && audioOnly.isEmpty()) throw ProviderException(
            ProviderException.Reason.UNSUPPORTED,
            "YouTube не вернул доступных форматов для этого видео.",
            ID,
        )
        val defaultHeight = heights.firstOrNull { it <= YouTubeFormats.MAX_DEFAULT_HEIGHT } ?: heights.lastOrNull()

        val items = heights.map { height ->
            val atHeight = videos.filter { it.height == height }
            val representative = atHeight.filter { it.sizeBytes != null }.maxByOrNull { it.sizeBytes ?: 0L }
            val size = representative?.sizeBytes?.let { video ->
                if (representative.hasAudio) video else audioSize?.let { video + it }
            }
            val fps = atHeight.mapNotNull { it.fps }.maxOrNull()?.takeIf { it > 30 }
            MediaItem(
                id = "${info.id}_${height}p",
                providerId = ID,
                type = MediaType.VIDEO,
                originalName = "${stem}_${height}p.mp4",
                downloadUrl = url,
                previewUrl = info.thumbnailUrl,
                sizeBytes = size,
                qualityLabel = "${height}p" + (fps?.toString() ?: ""),
                formatSelector = YouTubeFormats.videoSelector(height),
                group = VIDEO_GROUP,
                preselected = height == defaultHeight,
            )
        }
        if (!hasAudio) return items
        val music = musicCredit(info.title, info.uploader)
        val audio = MediaItem(
            id = "${info.id}_audio",
            providerId = ID,
            type = MediaType.AUDIO,
            originalName = "$stem.mp3",
            downloadUrl = url,
            previewUrl = info.thumbnailUrl,
            sizeBytes = audioSize,
            qualityLabel = "MP3",
            audioArtist = music?.first,
            audioTitle = music?.second,
            preselected = false,
        )
        return items + audio
    }

    private fun musicCredit(title: String, uploader: String?): Pair<String, String>? {
        val artist = title.substringBefore(" - ").trim()
        if (artist == title || artist.length !in 2..60) return null
        val channel = uploader?.trim()?.removePrefix("@") ?: return null
        val normalizedArtist = artist.filter(Char::isLetterOrDigit).lowercase()
        val normalizedChannel = channel.filter(Char::isLetterOrDigit).lowercase()
        if (normalizedArtist != normalizedChannel) return null
        val track = title.substringAfter(" - ").replace(
            Regex("\\s*\\((?:Official (?:Music )?Video|Official Audio|Lyric Video)\\)\\s*$", RegexOption.IGNORE_CASE),
            "",
        ).trim()
        return track.takeIf { it.length in 2..120 }?.let { artist to it }
    }

    companion object {
        const val ID = "youtube"
        const val VIDEO_GROUP = "video-quality"
    }
}
