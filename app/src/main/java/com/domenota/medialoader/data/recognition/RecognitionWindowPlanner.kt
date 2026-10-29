package com.domenota.medialoader.data.recognition

/**
 * Deterministic non-identical windows for acoustic retries.
 * The original automatic recognition uses the middle; retries explore the remainder
 * of the file without decoding the whole recording into memory.
 */
internal object RecognitionWindowPlanner {
    private val relativeOffsets = floatArrayOf(0.5f, 0.12f, 0.88f, 0.30f, 0.70f)

    /** Return null when the recording cannot provide a meaningfully different window. */
    fun startMs(durationMs: Long?, windowMs: Long, attempt: Int): Long? {
        if (attempt < 0 || windowMs <= 0) return null
        if (durationMs == null || durationMs <= windowMs) return if (attempt == 0) 0L else null
        val available = durationMs - windowMs
        val index = attempt.coerceAtMost(relativeOffsets.lastIndex)
        val chosen = (available * relativeOffsets[index]).toLong()
        if (index == 0) return chosen
        val center = (available * relativeOffsets[0]).toLong()
        // Very short tracks: a small seek shift sends almost the same fingerprint.
        if (kotlin.math.abs(chosen - center) < 4_000L) return null
        return chosen
    }
}
