package com.domenota.medialoader.core.recognition

/** Where the final artist/title pair came from. */
enum class RecognitionSource {
    SOURCE_METADATA,
    SHAZAM_DIRECT,
    SHAZAM_LOOP,
}

data class MusicRecognitionResult(
    val trackId: String? = null,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val source: RecognitionSource,
)

interface MusicRecognitionProvider {
    /**
     * Recognizes 16-bit signed mono PCM sampled at 16 kHz.
     * Returns null when the clip is too short or no reliable match is available.
     */
    suspend fun recognize(pcm16Mono16k: ShortArray): MusicRecognitionResult?
}

object RecognitionAudioSpec {
    const val SAMPLE_RATE_HZ = 16_000
    const val MIN_SECONDS = 4
    const val TARGET_SECONDS = 12

    const val MIN_SAMPLES = SAMPLE_RATE_HZ * MIN_SECONDS
    const val TARGET_SAMPLES = SAMPLE_RATE_HZ * TARGET_SECONDS
}
