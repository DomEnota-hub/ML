package com.domenota.medialoader.data.library

import kotlin.math.sqrt

/** A read-only second opinion for discarded 256-ms differences.
 * Small bounded PCM windows, independently decoded at 8 kHz, are aligned by
 * their unchanged surrounding context. This catches a removed word that is
 * masked by unchanged music in 64-band normalized spectral similarity.
 * No audio is stored, reported, sent off device, or used to authorize deletion.
 */
internal object AudioPcmShortDifference {
    private const val SAMPLES_PER_FINE_FRAME = 512 // 64 ms at 8 kHz
    private const val MAX_SHIFT = 192 // +/-24 ms of codec/encoder phase
    private const val MIN_CONTEXT_CORRELATION = .95
    private const val MAX_CONTEXT_RESIDUAL = .28
    private const val MIN_CORE_RESIDUAL = .29

    internal data class Evidence(
        val accepted: Boolean,
        val alignmentSamples: Int,
        val contextCorrelation: Double,
        val contextResidual: Double,
        val coreRatios: List<Double>,
    )

    fun confirms(
        first: FloatArray, second: FloatArray,
        contextFrames: Int = 2, coreFrames: Int = 4,
    ): Boolean = evaluate(first, second, contextFrames, coreFrames).accepted

    internal fun evaluate(
        first: FloatArray, second: FloatArray,
        contextFrames: Int = 2, coreFrames: Int = 4,
    ): Evidence {
        val no = Evidence(false, 0, 0.0, 1.0, emptyList())
        if (contextFrames < 2 || coreFrames < 2 ||
            first.any { !it.isFinite() } || second.any { !it.isFinite() }
        ) return no
        val coreStart = contextFrames * SAMPLES_PER_FINE_FRAME
        val coreEnd = coreStart + coreFrames * SAMPLES_PER_FINE_FRAME
        if (first.size < coreEnd + SAMPLES_PER_FINE_FRAME ||
            second.size < coreEnd + SAMPLES_PER_FINE_FRAME ||
            first.size > 8192 || second.size > 8192
        ) return no
        // Both context strips have to agree. Core samples are EXCLUDED from
        // offset/gain fitting so that a deleted voice cannot be fitted away.
        fun context(shift: Int): DoubleArray {
            var xx = 0.0; var yy = 0.0; var xy = 0.0; var count = 0
            for (i in first.indices) {
                if (i >= coreStart && i < coreEnd) continue
                val j = i + shift
                if (j !in second.indices) continue
                val a = first[i].toDouble(); val b = second[j].toDouble()
                xx += a * a; yy += b * b; xy += a * b; count++
            }
            if (count < SAMPLES_PER_FINE_FRAME * 2 || xx < 1e-5 || yy < 1e-5)
                return doubleArrayOf(-1.0, 0.0, 1.0)
            val correlation = xy / sqrt(xx * yy)
            // Optimal least-squares gain is robust to loudness differences.
            val gain = xy / yy
            val residual = sqrt((1.0 - correlation * correlation).coerceAtLeast(0.0))
            return doubleArrayOf(correlation, gain, residual)
        }
        var bestShift = 0
        var best = doubleArrayOf(-1.0, 0.0, 1.0)
        for (shift in -MAX_SHIFT..MAX_SHIFT step 4) {
            val score = context(shift)
            if (score[0] > best[0]) { best = score; bestShift = shift }
        }
        for (shift in maxOf(-MAX_SHIFT, bestShift - 3)..minOf(MAX_SHIFT, bestShift + 3)) {
            val score = context(shift)
            if (score[0] > best[0]) { best = score; bestShift = shift }
        }
        val correlation = best[0]; val gain = best[1]; val residual = best[2]
        if (correlation < MIN_CONTEXT_CORRELATION || residual > MAX_CONTEXT_RESIDUAL ||
            gain !in .15..6.0
        ) return Evidence(false, bestShift, correlation, residual, emptyList())
        val threshold = maxOf(MIN_CORE_RESIDUAL, residual * 1.55)
        val ratios = ArrayList<Double>(coreFrames)
        var consecutive = 0; var strongest = 0.0; var found = false
        for (frame in 0 until coreFrames) {
            val start = coreStart + frame * SAMPLES_PER_FINE_FRAME
            var xx = 0.0; var error = 0.0; var valid = 0
            for (i in start until start + SAMPLES_PER_FINE_FRAME) {
                val j = i + bestShift
                if (i !in first.indices || j !in second.indices) continue
                val a = first[i].toDouble(); val b = second[j].toDouble()
                xx += a*a; val difference = a - gain*b
                error += difference*difference; valid++
            }
            val ratio = if (valid >= SAMPLES_PER_FINE_FRAME * 3 / 4 && xx > valid * 1e-5)
                sqrt(error / xx) else 0.0
            ratios.add(ratio)
            if (ratio >= threshold) {
                consecutive++
                strongest = maxOf(strongest, ratio)
                if (consecutive >= 2 && strongest >= threshold + .06) found = true
            } else consecutive = 0
        }
        return Evidence(found, bestShift, correlation, residual, ratios)
    }
}
