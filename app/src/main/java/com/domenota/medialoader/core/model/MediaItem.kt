package com.domenota.medialoader.core.model

/** Source-neutral metadata supplied by a provider. */
data class MediaItem(
    val id: String,
    val providerId: String,
    val type: MediaType,
    val originalName: String,
    val downloadUrl: String,
    val previewUrl: String? = null,
    val sizeBytes: Long? = null,
    val audioAvailable: Boolean? = null,
    /** Optional source-provided music metadata. Filled before acoustic recognition when available. */
    val audioArtist: String? = null,
    val audioTitle: String? = null,
    val audioArtworkUrl: String? = null,
    /** Stable publication identifier. Separate queue submissions remain distinct batches. */
    val sourceGroupId: String? = null,
    val position: Int? = null,
    val customName: Boolean = false,
    val qualityLabel: String? = null,
    val formatSelector: String? = null,
    val group: String? = null,
    /** Original page/post URL entered by the user. Kept separate from the direct media/CDN URL. */
    val sourcePageUrl: String? = null,
    val preselected: Boolean = false,
)

enum class MediaType { PHOTO, VIDEO, AUDIO }
