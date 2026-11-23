package com.domenota.medialoader.data.library

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Transient 8-kHz mono PCM in small arrays. Never written to cache, session or logs.
 * The limit prevents an unusually long recording from exhausting the Android heap.
 * Longer files fall back to the existing spectral comparison rather than faking zones.
 */
internal class AudioPcmBlocks {
    companion object {
        const val RATE = 8_000
        const val MAX_SAMPLES = RATE * 60 * 10
        private const val CHUNK = 16_384
    }
    private val blocks = ArrayList<FloatArray>()
    var size: Int = 0
        private set
    fun append(sample: Float) {
        if (size >= MAX_SAMPLES) throw IllegalArgumentException("Audio comparison exceeds PCM memory limit")
        if (size % CHUNK == 0) blocks.add(FloatArray(CHUNK))
        blocks[size / CHUNK][size % CHUNK] = sample
        size++
    }
    operator fun get(index: Int): Float = blocks[index / CHUNK][index % CHUNK]
    fun valueOrNull(index: Int): Float? = if (index in 0 until size) get(index) else null
}

/** A full-stream, read-only, second opinion on two ALREADY grouped tracks.
 * Matches sub-frame codec delay on unchanged context and scans every supported
 * 64-ms interval. The coarse alignment maps edit boundaries, not a whitelist
 * of spectral candidates. Results cannot authorize trash.
 */
internal object AudioContinuousDifference {
    private const val FRAME = 512 // 64 ms at 8 kHz
    private const val MIN_SEGMENT_COLUMNS = 12
    private const val SEARCH_RADIUS = 2_048 // +/-256 ms relative to coarse frame map
    private const val CONTEXT_FRAMES = 15
    private const val MIN_CORRELATION = .90
    private const val MIN_RMS = .01
    private const val RELATIVE_THRESHOLD = 2.10
    private const val ABSOLUTE_THRESHOLD = .20
    private data class Segment(val startFirst: Int, val endFirst: Int, val deltaFrames: Int)
    private data class Point(val sample: Int, val value: Double, val rms: Double, val segment: Int)

    /** Indices of coarse time-grid columns covered by confirmed differences. */
    fun detect(
        first: AudioPcmBlocks, second: AudioPcmBlocks,
        aligned: AlignedAudioComparison,
        checkActive: () -> Unit = {},
    ): List<ComparisonZone> {
        if (first.size < 24_000 || second.size < 24_000) return emptyList()
        val stepSamples = (aligned.stepMs * AudioPcmBlocks.RATE / 1000).toInt()
        val columns = aligned.columns
        val firstToColumn = IntArray(aligned.first.size) { -1 }
        columns.forEachIndexed { i, c -> c.first?.let { if (it in firstToColumn.indices) firstToColumn[it] = i } }
        val points = ArrayList<Point>((first.size / FRAME).coerceAtMost(40_000))
        val segments = segments(columns)
        for ((segmentNumber, segment) in segments.withIndex()) {
            checkActive()
            val start = segment.startFirst * stepSamples
            val stop = min(segment.endFirst * stepSamples, first.size)
            val expected = segment.deltaFrames * stepSamples
            val offset = findOffset(first, second, start, stop, expected, checkActive) ?: continue
            val from = max(start, -offset)
            val to = min(stop, second.size - offset)
            if (to - from < FRAME * 24) continue
            val n = (to - from) / FRAME
            val xx = DoubleArray(n)
            val yy = DoubleArray(n)
            val xy = DoubleArray(n)
            val valid = BooleanArray(n)
            for (k in 0 until n) {
                if (k % 64 == 0) checkActive()
                val base = from + k * FRAME
                var a2 = 0.0; var b2 = 0.0; var ab = 0.0
                for (i in 0 until FRAME) {
                    val a = first[base + i].toDouble()
                    val b = second[base + i + offset].toDouble()
                    a2 += a * a; b2 += b * b; ab += a * b
                }
                xx[k] = a2; yy[k] = b2; xy[k] = ab
                valid[k] = sqrt(a2 / FRAME) > MIN_RMS
            }
            val raw = DoubleArray(n)
            for (k in 0 until n) {
                var cross = 0.0; var norm = 0.0
                for (j in max(0, k - CONTEXT_FRAMES)..min(n - 1, k + CONTEXT_FRAMES)) {
                    if (j == k) continue
                    cross += xy[j]; norm += yy[j]
                }
                val gain = (cross / max(norm, 1e-12)).coerceIn(.15, 6.0)
                val error = (xx[k] - 2.0 * gain * xy[k] + gain * gain * yy[k]).coerceAtLeast(0.0)
                raw[k] = sqrt(error / max(xx[k], 1e-12))
            }
            // Median-of-three eliminates one-frame MP3 impulses without muting
            // 128-ms words. Never smooth across an insertion/edit boundary.
            for (k in 1 until n - 1) {
                if (!valid[k]) continue
                val a = raw[k-1]; val b = raw[k]; val c = raw[k+1]
                val middle = max(min(a,b), min(max(a,b),c))
                points.add(Point(from + k * FRAME, middle, sqrt(xx[k] / FRAME), segmentNumber))
            }
        }
        val baseline = points.asSequence().filter { it.rms > MIN_RMS && it.value.isFinite() }
            .map { it.value }.sorted().toList()
        if (baseline.size < 32) return emptyList()
        val median = baseline[baseline.size / 2]
        if (!median.isFinite() || median > .75) return emptyList()
        val threshold = max(ABSOLUTE_THRESHOLD, median * RELATIVE_THRESHOLD)
        val accepted = ArrayList<ComparisonZone>()
        var start = -1
        var last = -1
        var streak = 0
        var lastSegment = -1
        fun flush() {
            if (streak >= 2 && start >= 0) {
                val firstFrame = start / stepSamples
                val lastFrame = (last + FRAME - 1) / stepSamples
                var lo = Int.MAX_VALUE; var hi = -1
                for (frame in firstFrame..lastFrame) {
                    if (frame in firstToColumn.indices) {
                        val col = firstToColumn[frame]
                        if (col >= 0) { lo = min(lo, col); hi = max(hi, col + 1) }
                    }
                }
                if (hi > lo && lo != Int.MAX_VALUE) accepted.add(ComparisonZone(lo, hi))
            }
            start = -1; last = -1; streak = 0
        }
        for (p in points) {
            val matching = p.value >= threshold && p.rms >= MIN_RMS
            if (!matching || (last >= 0 && (p.sample - last != FRAME || p.segment != lastSegment))) {
                flush()
            }
            if (matching) {
                if (start < 0) start = p.sample
                last = p.sample
                streak++
                lastSegment = p.segment
            }
        }
        flush()
        return accepted.distinct().sortedBy { it.start }
    }

    private fun segments(columns: List<ComparisonColumn>): List<Segment> {
        val found = ArrayList<Segment>()
        var start = -1; var prevFirst = -1; var prevSecond = -1; var delta = 0
        fun commit() {
            if (start >= 0 && prevFirst - start + 1 >= MIN_SEGMENT_COLUMNS)
                found.add(Segment(start, prevFirst + 1, delta))
            start = -1
        }
        for (c in columns) {
            val a = c.first; val b = c.second
            if (a == null || b == null) { commit(); continue }
            val nextDelta = b - a
            if (start < 0 || a != prevFirst + 1 || b != prevSecond + 1 || nextDelta != delta) {
                commit(); start = a; delta = nextDelta
            }
            prevFirst = a; prevSecond = b
        }
        commit()
        return found
    }

    /** Two-stage normalized cross-correlation across unchanged context.
     * Two independent anchors prevent phase aliasing and false global shifts.
     */
    private fun findOffset(
        first: AudioPcmBlocks, second: AudioPcmBlocks,
        from: Int, to: Int, expected: Int, checkActive: () -> Unit,
    ): Int? {
        if (to - from < 20_000) return null
        val length = 16_000 // two seconds
        val anchor1 = from + 2_048
        val anchor2 = from + (to - from) * 2 / 3
        fun score(offset: Int, anchor: Int, stride: Int): Double {
            if (anchor < 0 || anchor + length > first.size || anchor + offset < 0 ||
                anchor + offset + length > second.size) return -1.0
            var aa = 0.0; var bb = 0.0; var ab = 0.0
            for (i in 0 until length step stride) {
                val a = first[anchor + i].toDouble()
                val b = second[anchor + i + offset].toDouble()
                aa += a * a; bb += b * b; ab += a * b
            }
            return if (aa < .002 || bb < .002) -1.0 else ab / sqrt(aa * bb)
        }
        var best = -1.0; var lag = expected
        for (offset in expected - SEARCH_RADIUS..expected + SEARCH_RADIUS step 8) {
            if (offset % 256 == 0) checkActive()
            val corr = score(offset,anchor1,8)
            if (corr > best) {best = corr; lag = offset}
        }
        for (offset in lag - 8..lag + 8) {
            val corr = score(offset,anchor1,1)
            if (corr > best) { best = corr; lag = offset }
        }
        val secondScore = score(lag,anchor2,2)
        if (best < MIN_CORRELATION || secondScore < .85) return null
        return lag
    }
}
