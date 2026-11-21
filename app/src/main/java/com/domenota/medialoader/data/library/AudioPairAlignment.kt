package com.domenota.medialoader.data.library

import kotlin.math.abs
import kotlin.math.sqrt

/** Spectral frames on a shared, fixed-duration grid, not waveform heights. */
internal class ComparisonFrame(val spectrum: FloatArray, val rms: Float) {
    val silent: Boolean get() = spectrum.isEmpty()
}

internal data class ComparisonColumn(val first: Int?, val second: Int?, val different: Boolean)
internal data class ComparisonZone(val start: Int, val endExclusive: Int)
internal data class AlignedAudioComparison(
    val stepMs: Long,
    val first: List<ComparisonFrame>,
    val second: List<ComparisonFrame>,
    val columns: List<ComparisonColumn>,
    val zones: List<ComparisonZone>,
)

internal object AudioPairAlignment {
    const val BASE_STEP_MS = 256L
    private const val MAX_FRAMES = 1800
    private const val MATCH_THRESHOLD = .85f
    private const val GAP = .32f

    /** Same grid for both tracks. Bound memory and work even for long recordings. */
    fun stepMs(firstMs: Long?, secondMs: Long?): Long {
        val duration = maxOf(firstMs ?: 0L, secondMs ?: 0L)
        return when {
            duration <= BASE_STEP_MS * MAX_FRAMES -> BASE_STEP_MS
            duration <= 512L * MAX_FRAMES -> 512L
            else -> 1024L
        }
    }

    fun normalizedSpectrum(power: DoubleArray): FloatArray {
        val max = power.maxOrNull() ?: return FloatArray(0)
        if (!max.isFinite() || max < 1e-9) return FloatArray(0)
        // Compressed, centered spectral shape is invariant to global gain.
        // Unlike a hard log floor it tolerates small sub-frame timing shifts
        // and avoids amplifying codec noise in nearly empty bands.
        val values = FloatArray(power.size) { index ->
            sqrt(sqrt((power[index] / max).coerceIn(0.0, 1.0))).toFloat()
        }
        val mean = values.average().toFloat()
        for (index in values.indices) values[index] -= mean
        val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        if (norm <= 1e-5f) return FloatArray(0)
        for (index in values.indices) values[index] /= norm
        return values
    }

    private fun similarity(a: ComparisonFrame, b: ComparisonFrame): Float {
        if (a.silent || b.silent) return if (a.silent && b.silent) 1f else -1f
        if (a.spectrum.size != b.spectrum.size) return -1f
        var dot = 0f
        for (index in a.spectrum.indices) dot += a.spectrum[index] * b.spectrum[index]
        return dot.coerceIn(-1f, 1f)
    }

    /**
     * Suppress single-frame resampling gaps only when a small, distributed
     * rate drift is independently supported across a long matching span.
     * A real insertion/removal with distinct sound remains a difference.
     */
    private fun compensateSmallDrift(
        columns:List<ComparisonColumn>,first:List<ComparisonFrame>,second:List<ComparisonFrame>,
        stepMs:Long,
    ):List<ComparisonColumn>{
        val anchors=columns.filter{!it.different&&it.first!=null&&it.second!=null&&
            !first[it.first].silent&&!second[it.second].silent}
        if(anchors.size<48)return columns
        val start=anchors.first().first!!;val end=anchors.last().first!!
        if((end-start)*stepMs<30_000L)return columns
        val xMean=anchors.map{it.first!!.toDouble()}.average()
        val yMean=anchors.map{it.second!!.toDouble()}.average()
        val variance=anchors.sumOf{val x=it.first!!-xMean;x*x}
        if(variance<=0)return columns
        val ratio=anchors.sumOf{(it.first!!-xMean)*(it.second!!-yMean)}/variance
        if(abs(ratio-1.0) !in 0.007..0.035)return columns
        val intercept=yMean-ratio*xMean
        if(anchors.count{abs(it.second!!-(ratio*it.first!!+intercept))<=2.0} < anchors.size*.85)return columns
        val candidates=columns.indices.filter{i->
            if(i==0||i==columns.lastIndex)return@filter false
            val c=columns[i];val prev=columns[i-1];val next=columns[i+1]
            if(!c.different||prev.different||next.different||prev.first==null||prev.second==null||
                next.first==null||next.second==null)return@filter false
            if(c.first==null&&c.second!=null&&ratio>1.0){
                val b=second[c.second]
                !b.silent&&abs(c.second-(ratio*(prev.first+.5)+intercept))<=2.0&&
                    maxOf(similarity(first[prev.first],b),similarity(first[next.first],b))>=.9f
            }else if(c.first!=null&&c.second==null&&ratio<1.0){
                val a=first[c.first]
                !a.silent&&abs((prev.second+.5)-(ratio*c.first+intercept))<=2.0&&
                    maxOf(similarity(a,second[prev.second]),similarity(a,second[next.second]))>=.9f
            }else false
        }
        if(candidates.size<3||candidates.last()-candidates.first()<columns.size/3)return columns
        val corrected=candidates.toSet()
        return columns.mapIndexed{i,c->if(i in corrected)c.copy(different=false)else c}
    }

    /**
     * Bounded global sequence alignment with insertions/deletions. An offset or
     * an edit in the middle must not paint every subsequent matching frame red.
     * No result is preferable to inventing an alignment for unrelated recordings.
     */
    fun align(
        first: List<ComparisonFrame>, second: List<ComparisonFrame>, stepMs: Long,
        checkActive: () -> Unit = {},
    ): AlignedAudioComparison? {
        require(stepMs in setOf(256L, 512L, 1024L))
        val n = first.size; val m = second.size
        if (n !in 1..MAX_FRAMES || m !in 1..MAX_FRAMES) return null
        val width = m + 1
        val trace = ByteArray((n + 1) * width)
        var previous = FloatArray(width) { it * GAP }
        var current = FloatArray(width)
        for (j in 1..m) trace[j] = 2
        for (i in 1..n) {
            checkActive()
            current[0] = i * GAP
            trace[i * width] = 1
            for (j in 1..m) {
                val score = similarity(first[i - 1], second[j - 1])
                val diagonal = previous[j - 1] + (1f - score)
                val delete = previous[j] + GAP
                val insert = current[j - 1] + GAP
                val at = i * width + j
                when {
                    diagonal <= delete && diagonal <= insert -> {
                        current[j] = diagonal; trace[at] = 0
                    }
                    delete <= insert -> { current[j] = delete; trace[at] = 1 }
                    else -> { current[j] = insert; trace[at] = 2 }
                }
            }
            val swap = previous; previous = current; current = swap
        }
        val reversed = ArrayList<ComparisonColumn>(n + m)
        var i = n; var j = m
        var matches = 0
        var diverse = 0
        var lastMatch: ComparisonFrame? = null
        while (i > 0 || j > 0) {
            checkActive()
            when (trace[i * width + j].toInt()) {
                0 -> {
                    i--; j--
                    val a = first[i]; val b = second[j]
                    val match = similarity(a, b) >= MATCH_THRESHOLD
                    if (match && !a.silent && !b.silent) {
                        matches++
                        val last = lastMatch
                        if (last != null && similarity(last, a) < .97f) diverse++
                        lastMatch = a
                    }
                    reversed.add(ComparisonColumn(i, j, !match))
                }
                1 -> { i--; reversed.add(ComparisonColumn(i, null, true)) }
                else -> { j--; reversed.add(ComparisonColumn(null, j, true)) }
            }
        }
        // A few coincident spectral shapes (or stationary tones) are not
        // sufficient evidence to label an entire pair as synchronized.
        if (matches * stepMs < 6_000 || diverse < 4 ||
            matches < minOf(n, m) * .25f) return null
        val columns = compensateSmallDrift(reversed.asReversed().toList(),first,second,stepMs)
        val zones = ArrayList<ComparisonZone>()
        var start = -1
        for (index in 0..columns.size) {
            if (index < columns.size && columns[index].different) {
                if (start < 0) start = index
            } else if (start >= 0) {
                val gap = (start until index).any { columns[it].first == null || columns[it].second == null }
                // One noisy boundary frame alone is inconclusive; actual
                // missing/extra content is retained even when it is short.
                if (gap || (index - start) * stepMs >= 512) zones.add(ComparisonZone(start, index))
                start = -1
            }
        }
        return AlignedAudioComparison(stepMs, first, second, columns, zones)
    }
}
