package com.domenota.medialoader.data.library

import kotlin.math.*

/** Original spectral landmark implementation. No PCM hashes, network or native dependency.
 * Mono 8 kHz; 1024 point Hann FFT / 256 hop. Time differences survive leading trims.
 * Fingerprints indicate matching fragments, never that two whole recordings are interchangeable.
 */
internal data class AcousticFingerprint(val landmarks: List<AcousticLandmark>) {
    companion object { const val VERSION = 1 }
}
internal data class AcousticLandmark(val hash: Int, val frame: Int)
internal object AcousticFingerprints {
    const val RATE = 8000
    private const val N = 1024
    private const val HOP = 256
    private const val LIMIT = 6000
    class Builder(private val checkActive: () -> Unit = {}) {
        private val samples = FloatArray(N)
        private var filled = 0
        private var frame = 0
        private val anchors = ArrayDeque<Pair<Int,IntArray>>()
        // Deterministic reservoir ranks by content/time; bounds retained memory without truncating endings.
        private val reservoir = java.util.PriorityQueue<Pair<Long,AcousticLandmark>>(compareByDescending { it.first })
        fun accept(value: Float) {
            samples[filled++] = value
            if (filled != N) return
            checkActive()
            val peaks = peaks(samples)
            for ((time, previous) in anchors) {
                val delta = frame-time
                if (delta == 4 || delta == 8) for (a in previous) for (b in peaks) {
                    val mark = AcousticLandmark((a shl 15) or (b shl 5) or delta, time)
                    var rank = (mark.hash.toLong() shl 32) xor time.toLong()
                    rank = (rank xor (rank ushr 33)) * -49064778989728563L
                    rank = (rank xor (rank ushr 33)) * -4265267296055464877L
                    rank = (rank xor (rank ushr 33)) and Long.MAX_VALUE
                    if (reservoir.size < LIMIT) reservoir.add(rank to mark)
                    else if (rank < reservoir.peek().first) { reservoir.poll(); reservoir.add(rank to mark) }
                }
            }
            if (frame % 8 == 0) anchors.addLast(frame to peaks)
            while (anchors.isNotEmpty() && frame-anchors.first().first >= 8) anchors.removeFirst()
            frame++
            samples.copyInto(samples,0,HOP,N); filled = N-HOP
        }
        fun finish() = AcousticFingerprint(reservoir.map { it.second }.sortedBy { it.frame })
    }
    private fun peaks(samples: FloatArray): IntArray {
        val re = DoubleArray(N) { samples[it]* (0.5-0.5*cos(2*PI*it/(N-1))) }
        val im = DoubleArray(N)
        var j = 0
        for (i in 1 until N) { var bit = N shr 1; while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit
            if (i < j) { val v = re[i]; re[i] = re[j]; re[j] = v }
        }
        var length = 2
        while (length <= N) {
            val angle = -2*PI/length
            for (start in 0 until N step length) for (k in 0 until length/2) {
                val c = cos(angle*k); val s = sin(angle*k); val a = start+k; val b = a+length/2
                val tr = re[b]*c-im[b]*s; val ti = re[b]*s+im[b]*c
                re[b] = re[a]-tr; im[b] = im[a]-ti; re[a] += tr; im[a] += ti
            }; length *= 2
        }
        val power = DoubleArray(N/2) { re[it]*re[it]+im[it]*im[it] }
        if (power.maxOrNull()!! < 1e-9) return intArrayOf()
        // One strongest local peak per broad band, exact bin landmark (not amplitude).
        return listOf(8..24,25..56,57..112,113..224,225..480).mapNotNull { band ->
            band.filter { power[it] > power[it-1] && power[it] >= power[it+1] }
                .maxByOrNull { power[it] }?.takeIf { power[it] > 1e-8 }
        }.toIntArray()
    }
    /** Offset voting; repeated stationary tones cannot establish enough distinct evidence. */
    fun matches(a: AcousticFingerprint, b: AcousticFingerprint, checkActive: () -> Unit = {}): Boolean {
        val byHash = b.landmarks.groupBy { it.hash }
        val votes = HashMap<Int,MutableSet<Int>>()
        val spans = HashMap<Int,Pair<Int,Int>>()
        for (mark in a.landmarks) {
            checkActive()
            for (other in byHash[mark.hash].orEmpty().take(12)) {
                val offset = other.frame-mark.frame
                for (bucket in (offset-1)..(offset+1)) {
                    votes.getOrPut(bucket) { HashSet() }.add(mark.hash)
                    val span = spans[bucket]
                    spans[bucket] = minOf(span?.first ?: mark.frame,mark.frame) to maxOf(span?.second ?: mark.frame,mark.frame)
                }
            }
        }
        return votes.any { (offset, hashes) -> hashes.size >= 90 &&
            spans.getValue(offset).let { (lo,hi) -> (hi-lo)*HOP >= RATE*6 } }
    }
    /** Inverted index avoids testing every library pair. Oversubscribed hashes are ignored. */
    fun candidatePairs(input: Map<String,AcousticFingerprint>, checkActive: () -> Unit = {}): Set<Pair<String,String>> {
        val index = HashMap<Int,MutableSet<String>>()
        input.forEach { (id,fp) -> checkActive(); fp.landmarks.forEach { mark -> index.getOrPut(mark.hash) { HashSet() }.let { if (it.size <= 40) it.add(id) } } }
        val counts = HashMap<Pair<String,String>,Int>()
        for (ids in index.values) { checkActive(); if (ids.size in 2..40) {
            val sorted = ids.sorted()
            for (i in sorted.indices) for (j in i+1 until sorted.size) {
                val pair = sorted[i] to sorted[j]; counts[pair] = (counts[pair] ?: 0)+1
            }
        }
        }
        return counts.filterValues { it >= 90 }.keys
    }
}
