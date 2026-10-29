package com.domenota.medialoader.core.provider

/** Everything the app needs to know about one video, independent of the library that found it. */
data class StreamInfo(
    val id: String,
    val title: String,
    val uploader: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    val formats: List<StreamFormat>,
)

data class StreamFormat(
    val formatId: String,
    val height: Int?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val ext: String?,
    val sizeBytes: Long?,
    val fps: Int?,
    val width: Int? = null,
    /** Ephemeral playback metadata, never stored in download history. */
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val protocol: String? = null,
    val manifestUrl: String? = null,
    val hasDrm: Boolean = false,
)

class ExtractionException(message: String, val kind: Kind = Kind.FAILED, cause: Throwable? = null) :
    Exception(message, cause) {
    /**
     * UNAVAILABLE: private, removed or blocked video.
     * AUTH_REQUIRED: YouTube explicitly requires an authenticated session.
     * FAILED: anything else that may work later.
     */
    enum class Kind { UNAVAILABLE, AUTH_REQUIRED, FAILED }
}

/** Narrow seam in front of the extraction library (yt-dlp), so the library can be replaced. */
interface StreamExtractor {
    suspend fun extract(url: String): StreamInfo
}
