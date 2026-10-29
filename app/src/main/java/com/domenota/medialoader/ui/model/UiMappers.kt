package com.domenota.medialoader.ui.model

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.data.storage.SavedFileStatus
import com.domenota.medialoader.core.model.FileSizeFormatter
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun MediaType.kind(): MediaKind = when (this) {
    MediaType.PHOTO -> MediaKind.IMAGE
    MediaType.VIDEO -> MediaKind.VIDEO
    MediaType.AUDIO -> MediaKind.AUDIO
}

private fun MediaType.label(): String = when (this) {
    MediaType.PHOTO -> "Фото · JPG"
    MediaType.VIDEO -> "Видео · MP4"
    MediaType.AUDIO -> "Аудио · MP3"
}

fun MediaItem.toPreviewUi(selected: Boolean) = PreviewMediaUi(
    id = id,
    title = originalName,
    subtitle = qualityLabel?.let { "$it · ${type.label()}" } ?: type.label(),
    sizeLabel = sizeBytes?.let(FileSizeFormatter::format) ?: if (type == MediaType.AUDIO)
        "Размер после обработки" else "Размер неизвестен",
    kind = type.kind(),
    selected = selected,
    previewUrl = previewUrl,
    providerId = providerId,
    fullImageUrl = downloadUrl.takeIf { type == MediaType.PHOTO },
)

fun DownloadEntity.toUi(fileStatus: SavedFileStatus = SavedFileStatus.UNCHECKED): DownloadUiItem {
    val uiState = when (state) {
        DownloadState.QUEUED -> DownloadUiState.QUEUED
        DownloadState.RUNNING -> DownloadUiState.DOWNLOADING
        DownloadState.COMPLETED -> DownloadUiState.COMPLETED
        DownloadState.FAILED -> DownloadUiState.FAILED
        DownloadState.CANCELLED -> DownloadUiState.CANCELLED
    }
    val progress = if (state == DownloadState.RUNNING && sizeBytes != null && sizeBytes > 0) {
        (bytesDownloaded.toFloat() / sizeBytes.toFloat()).coerceIn(0f, 1f)
    } else null
    val statusText = when (state) {
        DownloadState.FAILED -> errorMessage ?: "Ошибка загрузки"
        else -> if (mediaType == MediaType.PHOTO) {
            val extension = originalName.substringAfterLast('.', "JPG").uppercase(Locale.ROOT)
            "Фото · ${extension.takeIf { it in setOf("JPG", "JPEG", "PNG", "WEBP") } ?: "JPG"}"
        } else mediaType.label()
    }
    return DownloadUiItem(
        id = id,
        title = originalName,
        subtitle = statusText,
        sizeLabel = if (state == DownloadState.COMPLETED) sizeBytes?.let(FileSizeFormatter::format) else null,
        durationMs = durationMs,
        durationLabel = durationMs
            ?.takeIf { mediaType == MediaType.VIDEO && it > 0L }
            ?.let(::formatDurationLabel),
        state = uiState,
        progress = progress,
        dateLabel = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(createdAtEpochMillis)),
        // Embedded artwork is read first by DownloadThumbnail. If absent after a
        // confirmed audio match, use its cover before the publication preview.
        previewUrl = if (mediaType == MediaType.AUDIO && recognitionSource != null)
            recognitionArtworkUrl ?: previewUrl else previewUrl ?: recognitionArtworkUrl,
        savedUri = savedUri,
        kind = mediaType.kind(),
        groupId = groupId,
        providerId = providerId,
        recognitionLabel = recognitionSource?.let { source ->
            val origin = when (source) {
                "SOURCE_METADATA" -> "Instagram"
                "SHAZAM_DIRECT" -> "Shazam"
                "SHAZAM_LOOP" -> "Shazam · loop"
                else -> source
            }
            listOfNotNull(recognizedArtist, recognizedTitle).joinToString(" — ")
                .takeIf { it.isNotBlank() }?.let { "$it · $origin" } ?: origin
        },
        recognized = recognitionSource != null,
        recognizedArtist = recognizedArtist,
        recognizedTitle = recognizedTitle,
        recognizedArtworkUrl = recognitionArtworkUrl,
        sourcePageUrl = sourcePageUrl,
        fileStatus = if (state == DownloadState.COMPLETED && !hidden) fileStatus else SavedFileStatus.UNCHECKED,
    )
}

/** Optional audio tracks are never selected by default. */
fun List<MediaItem>.defaultSelectedIds(): Set<String> =
    filter { item ->
        if (item.group != null || item.providerId == "youtube") item.preselected
        else item.type != MediaType.AUDIO
    }
        .let { defaults -> if (none { it.type == MediaType.PHOTO }) defaults.take(1) else defaults }
        .map { it.id }.toSet()


private fun formatDurationLabel(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
