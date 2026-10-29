package com.domenota.medialoader.ui.model

import com.domenota.medialoader.data.storage.SavedFileStatus

enum class AnalysisUiState {
    IDLE,
    ANALYZING,
    PREVIEW_READY,
    ACCESS_REQUIRED,
    ERROR,
}

enum class MediaKind {
    IMAGE,
    VIDEO,
    AUDIO,
}

data class PreviewMediaUi(
    val id: String,
    val title: String,
    val subtitle: String,
    val sizeLabel: String?,
    val kind: MediaKind,
    val selected: Boolean,
    val previewUrl: String? = null,
    val providerId: String? = null,
    /** Full photo URL; video/audio streams are resolved separately, never from webpage URLs. */
    val fullImageUrl: String? = null,
)

enum class DownloadUiState {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class DownloadUiItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val sizeLabel: String?,
    val durationLabel: String? = null,
    val state: DownloadUiState,
    val progress: Float? = null,
    val dateLabel: String? = null,
    val previewUrl: String? = null,
    val savedUri: String? = null,
    val kind: MediaKind = MediaKind.IMAGE,
    val groupId: String? = null,
    val providerId: String? = null,
    val recognitionLabel: String? = null,
    val recognizedArtist: String? = null,
    val recognizedTitle: String? = null,
    val recognizedArtworkUrl: String? = null,
    val recognized: Boolean = false,
    val sourcePageUrl: String? = null,
    val fileStatus: SavedFileStatus = SavedFileStatus.UNCHECKED,
    val durationMs: Long? = null,
)
