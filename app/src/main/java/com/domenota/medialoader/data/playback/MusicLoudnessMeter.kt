package com.domenota.medialoader.data.playback

import kotlin.math.*

/** Integrated K-weighted, absolute/relative gated loudness for mono/stereo PCM.
 * Sample peak is NOT a true-peak measurement. No source samples are retained.
 */
internal data class MusicLoudnessMeasurement(val lufs: Double, val samplePeak: Double)

internal object MusicLoudnessPolicy {
    const val TARGET_LUFS = -18.0
    private val peakCeiling = 10.0.pow(-1.0 / 20.0)
    fun gain(measurement: MusicLoudnessMeasurement?): Double {
        if (measurement == null || !measurement.lufs.isFinite() ||
            !measurement.samplePeak.isFinite() || measurement.samplePeak <= 0.0) return 1.0
        val nominal = 10.0.pow((TARGET_LUFS - measurement.lufs).coerceIn(-24.0, 6.0) / 20.0)
        return min(nominal, peakCeiling / measurement.samplePeak).coerceIn(0.0, 2.0)
    }
}

internal class MusicLoudnessMeter(private val rate: Int, private val channels: Int) {
    init { require(rate in 8000..192000 && channels in 1..2) }
    private val hop = rate / 10
    private val filters = Array(channels) { KWeight(rate) }
    private val ring = DoubleArray(4)
    private val blocks = DoubleArray(36_050) // One hour, including integer hop rounding at uncommon rates.
    private var hops = 0
    private var count = 0
    private var sum = 0.0
    private var size = 0
    var frames = 0L
        private set
    private var peak = 0.0

    fun accept(left: Double, right: Double = 0.0) {
        require(frames < rate * 3600L)
        fun energy(raw: Double, channel: Int): Double {
            require(raw.isFinite())
            peak = max(peak, abs(raw))
            val weighted = filters[channel].sample(raw)
            return weighted * weighted
        }
        sum += energy(left, 0)
        if (channels == 2) sum += energy(right, 1)
        frames++
        if (++count == hop) {
            ring[hops % 4] = sum / hop
            hops++
            if (hops >= 4) blocks[size++] = ring.sum() / 4.0
            sum = 0.0
            count = 0
        }
    }

    fun finish(): MusicLoudnessMeasurement? {
        if (size == 0 || peak <= 0.0) return null
        val absolute = 10.0.pow((-70.0 + .691) / 10.0)
        fun meanAbove(threshold: Double): Double? {
            var energy = 0.0
            var included = 0
            for (i in 0 until size) if (blocks[i].isFinite() && blocks[i] >= threshold) {
                energy += blocks[i]
                included++
            }
            return if (included > 0) energy / included else null
        }
        val absoluteMean = meanAbove(absolute) ?: return null
        val integrated = meanAbove(max(absolute, absoluteMean / 10.0)) ?: return null
        return MusicLoudnessMeasurement(-.691 + 10.0 * log10(integrated), peak)
    }

    private class Biquad(private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double) {
        private var z1 = 0.0
        private var z2 = 0.0
        fun sample(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    private class KWeight(rate: Int) {
        private val shelf: Biquad
        private val highPass: Biquad
        init {
            val k = tan(PI * 1681.974450955533 / rate)
            val q = .7071752369554196
            val vh = 10.0.pow(3.999843853973347 / 20.0)
            val vb = vh.pow(.4996667741545416)
            val a0 = 1 + k/q + k*k
            shelf = Biquad((vh + vb*k/q + k*k)/a0, 2*(k*k-vh)/a0,
                (vh-vb*k/q+k*k)/a0, 2*(k*k-1)/a0, (1-k/q+k*k)/a0)
            val h = tan(PI * 38.13547087602444 / rate)
            val hq = .5003270373238773
            val d = 1 + h/hq + h*h
            highPass = Biquad(1.0, -2.0, 1.0, 2*(h*h-1)/d, (1-h/hq+h*h)/d)
        }
        fun sample(x: Double) = highPass.sample(shelf.sample(x))
    }
}
