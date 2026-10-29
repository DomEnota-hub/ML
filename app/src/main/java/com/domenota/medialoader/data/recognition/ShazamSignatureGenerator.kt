package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.RecognitionAudioSpec
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

/**
 * Shazam fingerprint encoder adapted from the MIT-licensed shazamio-core algorithm.
 * See THIRD_PARTY_NOTICES.md for attribution.
 */
internal object ShazamSignatureGenerator {
    data class Signature(
        val uri: String,
        val sampleMs: Long,
    )

    private data class Peak(
        val fftPass: Int,
        val magnitude: Int,
        val correctedBin: Int,
    )

    private enum class Band(val id: Int) {
        HZ_250_520(0),
        HZ_520_1450(1),
        HZ_1450_3500(2),
        HZ_3500_5500(3),
    }

    fun generate(samples: ShortArray): Signature {
        val state = GeneratorState(samples.size)
        var offset = 0
        while (offset + FFT_STEP <= samples.size) {
            state.accept(samples, offset)
            offset += FFT_STEP
        }
        val uri = DATA_URI_PREFIX + Base64.getEncoder().encodeToString(encode(state, samples.size))
        return Signature(
            uri = uri,
            sampleMs = samples.size * 1000L / RecognitionAudioSpec.SAMPLE_RATE_HZ,
        )
    }

    private class GeneratorState(sampleCount: Int) {
        private val ring = ShortArray(FFT_SIZE)
        private val windowed = DoubleArray(FFT_SIZE)
        private val fftOutputs = Array(HISTORY) { FloatArray(USEFUL_BINS) }
        private val spreadOutputs = Array(HISTORY) { FloatArray(USEFUL_BINS) }
        private val fft = Radix2Fft(FFT_SIZE)

        private var ringIndex = 0
        private var fftIndex = 0
        private var spreadIndex = 0
        private var spreadCount = 0

        val peaks = Band.entries.associateWith { mutableListOf<Peak>() }

        init {
            require(sampleCount >= 0)
        }

        fun accept(source: ShortArray, sourceOffset: Int) {
            for (index in 0 until FFT_STEP) {
                ring[ringIndex + index] = source[sourceOffset + index]
            }
            ringIndex = (ringIndex + FFT_STEP) and (FFT_SIZE - 1)

            for (index in 0 until FFT_SIZE) {
                windowed[index] =
                    ring[(index + ringIndex) and (FFT_SIZE - 1)].toDouble() * HANNING[index]
            }

            val real = windowed.copyOf()
            val imag = DoubleArray(FFT_SIZE)
            fft.transform(real, imag)

            val output = fftOutputs[fftIndex]
            for (bin in 0..1024) {
                val power = (real[bin] * real[bin] + imag[bin] * imag[bin]) / FFT_POWER_SCALE
                output[bin] = max(power, MIN_POWER).toFloat()
            }
            fftIndex = (fftIndex + 1) and (HISTORY - 1)

            spreadLatest()
            spreadCount++
            if (spreadCount >= RECOGNITION_DELAY) recognizePeaks()
        }

        private fun spreadLatest() {
            val latest = fftOutputs[(fftIndex - 1) and (HISTORY - 1)]
            val spread = spreadOutputs[spreadIndex]
            latest.copyInto(spread)

            for (position in 0..1022) {
                spread[position] = maxOf(spread[position], spread[position + 1], spread[position + 2])
            }

            val snapshot = spread.copyOf()
            for (position in 0..1024) {
                for (former in SPREAD_BACK) {
                    val old = spreadOutputs[(spreadIndex - former) and (HISTORY - 1)]
                    old[position] = max(old[position], snapshot[position])
                }
            }

            spreadIndex = (spreadIndex + 1) and (HISTORY - 1)
        }

        private fun recognizePeaks() {
            val candidate = fftOutputs[(fftIndex - RECOGNITION_DELAY) and (HISTORY - 1)]
            val neighborhood = spreadOutputs[(spreadIndex - 49) and (HISTORY - 1)]

            for (bin in 10..1014) {
                val value = candidate[bin]
                if (value < 1f / 64f || value < neighborhood[bin - 1]) continue

                var neighborMax = 0f
                for (delta in FREQUENCY_NEIGHBORS) {
                    neighborMax = max(neighborMax, neighborhood[bin + delta])
                }
                if (value <= neighborMax) continue

                var timeMax = neighborMax
                for (delta in TIME_NEIGHBORS) {
                    val other = spreadOutputs[(spreadIndex + delta) and (HISTORY - 1)]
                    timeMax = max(timeMax, other[bin - 1])
                }
                if (value <= timeMax) continue

                val center = magnitude(value)
                val before = magnitude(candidate[bin - 1])
                val after = magnitude(candidate[bin + 1])
                val curvature = center * 2f - before - after
                if (curvature < 0f) continue

                val correction = if (curvature == 0f) 0f else (after - before) * 32f / curvature
                val correctedBin = (bin * 64 + correction.toInt()).coerceIn(0, 0xffff)
                val frequencyHz = correctedBin * (16_000f / 2f / 1024f / 64f)

                val band = when (frequencyHz.toInt()) {
                    in 250..519 -> Band.HZ_250_520
                    in 520..1449 -> Band.HZ_520_1450
                    in 1450..3499 -> Band.HZ_1450_3500
                    in 3500..5500 -> Band.HZ_3500_5500
                    else -> null
                } ?: continue

                peaks.getValue(band) += Peak(
                    fftPass = spreadCount - RECOGNITION_DELAY,
                    magnitude = center.toInt().coerceIn(0, 0xffff),
                    correctedBin = correctedBin,
                )
            }
        }

        private fun magnitude(power: Float): Float =
            (max(ln(power.toDouble()).toFloat(), 1f / 64f) * 1477.3f) + 6144f
    }

    private fun encode(state: GeneratorState, numberSamples: Int): ByteArray {
        val bandContents = ByteArrayOutputStream()

        for (band in Band.entries) {
            val bandPeaks = state.peaks.getValue(band)
            if (bandPeaks.isEmpty()) continue

            val peakBytes = ByteArrayOutputStream()
            var lastPass = 0
            for (peak in bandPeaks) {
                val delta = peak.fftPass - lastPass
                if (delta >= 255) {
                    peakBytes.write(0xff)
                    peakBytes.writeU32LE(peak.fftPass.toLong())
                    lastPass = peak.fftPass
                }

                peakBytes.write(peak.fftPass - lastPass)
                peakBytes.writeU16LE(peak.magnitude)
                peakBytes.writeU16LE(peak.correctedBin)
                lastPass = peak.fftPass
            }

            val payload = peakBytes.toByteArray()
            bandContents.writeU32LE(0x60030040L + band.id)
            bandContents.writeU32LE(payload.size.toLong())
            bandContents.write(payload)
            repeat((4 - payload.size % 4) % 4) { bandContents.write(0) }
        }

        val bands = bandContents.toByteArray()
        val bodySize = 8 + bands.size

        val full = ByteArrayOutputStream()
        full.writeU32LE(0xcafe2580L)
        full.writeU32LE(0)
        full.writeU32LE(bodySize.toLong())
        full.writeU32LE(0x94119c00L)
        repeat(3) { full.writeU32LE(0) }
        full.writeU32LE(3L shl 27)
        repeat(2) { full.writeU32LE(0) }
        full.writeU32LE(numberSamples.toLong() + 3840L)
        full.writeU32LE(((15 shl 19) + 0x40000).toLong())

        full.writeU32LE(0x40000000L)
        full.writeU32LE(bodySize.toLong())
        full.write(bands)

        val bytes = full.toByteArray()
        val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value
        bytes.putU32LE(4, crc)
        return bytes
    }

    private class Radix2Fft(private val size: Int) {
        fun transform(real: DoubleArray, imag: DoubleArray) {
            require(real.size == size && imag.size == size)
            var j = 0
            for (i in 1 until size) {
                var bit = size shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val tr = real[i]
                    real[i] = real[j]
                    real[j] = tr
                    val ti = imag[i]
                    imag[i] = imag[j]
                    imag[j] = ti
                }
            }

            var length = 2
            while (length <= size) {
                val angle = -2.0 * PI / length
                val baseRe = cos(angle)
                val baseIm = kotlin.math.sin(angle)
                val half = length / 2

                var start = 0
                while (start < size) {
                    var wRe = 1.0
                    var wIm = 0.0
                    for (offset in 0 until half) {
                        val even = start + offset
                        val odd = even + half

                        val oddRe = real[odd] * wRe - imag[odd] * wIm
                        val oddIm = real[odd] * wIm + imag[odd] * wRe
                        val evenRe = real[even]
                        val evenIm = imag[even]

                        real[even] = evenRe + oddRe
                        imag[even] = evenIm + oddIm
                        real[odd] = evenRe - oddRe
                        imag[odd] = evenIm - oddIm

                        val nextRe = wRe * baseRe - wIm * baseIm
                        wIm = wRe * baseIm + wIm * baseRe
                        wRe = nextRe
                    }
                    start += length
                }
                length = length shl 1
            }
        }
    }

    private fun ByteArrayOutputStream.writeU16LE(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeU32LE(value: Long) {
        write((value and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 24) and 0xff).toInt())
    }

    private fun ByteArray.putU32LE(offset: Int, value: Long) {
        this[offset] = (value and 0xff).toByte()
        this[offset + 1] = ((value ushr 8) and 0xff).toByte()
        this[offset + 2] = ((value ushr 16) and 0xff).toByte()
        this[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private const val DATA_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"
    private const val FFT_SIZE = 2048
    private const val FFT_STEP = 128
    private const val USEFUL_BINS = 1025
    private const val HISTORY = 256
    private const val RECOGNITION_DELAY = 46
    private const val FFT_POWER_SCALE = 131072.0
    private const val MIN_POWER = 0.0000000001

    private val SPREAD_BACK = intArrayOf(1, 3, 6)
    private val FREQUENCY_NEIGHBORS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
    private val TIME_NEIGHBORS =
        intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

    // shazamio-core stores this 2048-value window as constants. It is the same
    // symmetric Hann window with the zero-valued endpoints omitted.
    private val HANNING = DoubleArray(FFT_SIZE) { index ->
        0.5 - 0.5 * cos(2.0 * PI * (index + 1).toDouble() / (FFT_SIZE + 1).toDouble())
    }
}
