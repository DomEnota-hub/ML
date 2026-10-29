package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.MusicRecognitionProvider
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.core.recognition.RecognitionAudioSpec
import com.domenota.medialoader.core.recognition.RecognitionSource

/**
 * Shazam recognizer with an Instagram-friendly short-loop fallback.
 *
 *  - < 4 s: skip, there is too little unique material
 *  - 4..<12 s: try the real clip first, then two looped 12 s variants
 *  - >= 12 s: try only the middle 12 s
 *
 * A looped result is accepted only when both differently-phased loops resolve to the same track id.
 */
internal class ShazamRecognitionProvider(
    private val api: ShazamApi = ShazamClient(),
    private val signatureFactory: (ShortArray) -> ShazamSignatureGenerator.Signature =
        ShazamSignatureGenerator::generate,
) : MusicRecognitionProvider {

    override suspend fun recognize(pcm16Mono16k: ShortArray): MusicRecognitionResult? {
        if (!RecognitionAudio.eligible(pcm16Mono16k)) return null

        val direct = RecognitionAudio.directWindow(pcm16Mono16k)
        api.recognize(signatureFactory(direct))?.let {
            return it.copy(source = RecognitionSource.SHAZAM_DIRECT)
        }

        if (pcm16Mono16k.size >= RecognitionAudioSpec.TARGET_SAMPLES) return null

        val loopA = RecognitionAudio.loopToTarget(pcm16Mono16k)
        val loopB = RecognitionAudio.loopToTarget(
            pcm16Mono16k,
            RecognitionAudio.confirmationPhase(pcm16Mono16k),
        )

        val first = api.recognize(signatureFactory(loopA)) ?: return null
        val second = api.recognize(signatureFactory(loopB)) ?: return null

        val firstId = first.trackId?.takeIf { it.isNotBlank() } ?: return null
        val secondId = second.trackId?.takeIf { it.isNotBlank() } ?: return null
        if (firstId != secondId) return null

        return first.copy(source = RecognitionSource.SHAZAM_LOOP)
    }
}
