package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.RecognitionAudioSpec

/** Pure helpers used by the recognizer and covered by JVM tests. */
internal object RecognitionAudio {
    fun durationMs(samples: ShortArray): Long =
        samples.size * 1000L / RecognitionAudioSpec.SAMPLE_RATE_HZ

    fun eligible(samples: ShortArray): Boolean =
        samples.size >= RecognitionAudioSpec.MIN_SAMPLES

    /** Shazam clients use a 12-second window. Longer files are sampled from the middle. */
    fun directWindow(samples: ShortArray): ShortArray {
        val target = RecognitionAudioSpec.TARGET_SAMPLES
        if (samples.size <= target) return samples
        val start = (samples.size - target) / 2
        return samples.copyOfRange(start, start + target)
    }

    /**
     * Repeats a short clip in memory until it fills the 12-second Shazam window.
     * phaseOffset makes the artificial join land at a different point for confirmation.
     */
    fun loopToTarget(samples: ShortArray, phaseOffset: Int = 0): ShortArray {
        require(samples.isNotEmpty())
        val target = RecognitionAudioSpec.TARGET_SAMPLES
        if (samples.size >= target) return directWindow(samples)

        val normalizedOffset = ((phaseOffset % samples.size) + samples.size) % samples.size
        return ShortArray(target) { index ->
            samples[(normalizedOffset + index) % samples.size]
        }
    }

    fun confirmationPhase(samples: ShortArray): Int = samples.size / 2
}
