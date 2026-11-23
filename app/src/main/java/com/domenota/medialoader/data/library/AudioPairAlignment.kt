package com.domenota.medialoader.data.library

import kotlin.math.abs
import kotlin.math.sqrt

/** Spectral frames on a shared, fixed-duration grid, not waveform heights. */
internal class ComparisonFrame(val spectrum: FloatArray, val rms: Float) {
    val silent: Boolean get() = spectrum.isEmpty()
}

internal data class ComparisonColumn(val first: Int?, val second: Int?, val different: Boolean)
internal data class ComparisonZone(val start: Int, val endExclusive: Int)
internal enum class ComparisonZoneKind { SILENT_PADDING, QUIET_EDGE_UNCERTAIN, EXTRA_IN_VERSION, MISSING_IN_VERSION, CHANGED_CONTENT, SHORT_CHANGE_UNCERTAIN }
internal data class AlignedAudioComparison(
    val stepMs: Long,
    val first: List<ComparisonFrame>,
    val second: List<ComparisonFrame>,
    val columns: List<ComparisonColumn>,
    val zones: List<ComparisonZone>,
    val evaluatedCells: Long = 0,
    /** One coarse spectral frame that needs a second, narrower confirmation. */
    val shortCandidates: List<ComparisonZone> = emptyList(),
    val confirmedShortZones: Set<ComparisonZone> = emptySet(),
) {
    fun withConfirmedShortZones(confirmed: Set<ComparisonZone>): AlignedAudioComparison {
        val accepted = confirmed.intersect(shortCandidates.toSet())
        return copy(zones = (zones + accepted).distinct().sortedBy { it.start },
            shortCandidates = emptyList(), confirmedShortZones = accepted)
    }
    /** Keep uncertain low-level recording edges visible without calling them musical edits.
     * This is only a conservative UI classification: even a quiet tail can be audible
     * on some equipment. Neither these labels nor the comparison authorize deletion. */
    fun zoneKind(zone: ComparisonZone): ComparisonZoneKind {
        if (zone in confirmedShortZones) return ComparisonZoneKind.SHORT_CHANGE_UNCERTAIN
        val section = columns.subList(zone.start, zone.endExclusive)
        val extra = section.all { it.first == null && it.second != null }
        val missing = section.all { it.second == null && it.first != null }
        val boundary = zone.start == 0 || zone.endExclusive == columns.size
        if (boundary && (extra || missing) && section.all { column ->
                val frame = column.first?.let(first::get) ?: second[requireNotNull(column.second)]
                frame.silent && frame.rms <= .003f
            }) return ComparisonZoneKind.SILENT_PADDING
        // Normalized spectra can turn tiny decoder residuals into apparently different
        // shapes. Require BOTH files to be consistently very quiet across at least
        // two edge frames. Keep the zone inspectable and explicitly uncertain.
        if (boundary && section.size >= 2) {
            val firstPeak = (first.maxOfOrNull { it.rms } ?: 0f).coerceAtLeast(1e-6f)
            val secondPeak = (second.maxOfOrNull { it.rms } ?: 0f).coerceAtLeast(1e-6f)
            fun quiet(frame: ComparisonFrame, peak: Float) =
                frame.rms.isFinite() && frame.rms <= .00075f && frame.rms <= peak * .006f
            val lowOnBoth = section.all { column ->
                (column.first == null || quiet(first[column.first], firstPeak)) &&
                    (column.second == null || quiet(second[column.second], secondPeak))
            }
            if (lowOnBoth) return ComparisonZoneKind.QUIET_EDGE_UNCERTAIN
        }
        return when {
            extra -> ComparisonZoneKind.EXTRA_IN_VERSION
            missing -> ComparisonZoneKind.MISSING_IN_VERSION
            else -> ComparisonZoneKind.CHANGED_CONTENT
        }
    }
}

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
        // Try a short band first. Non-negative diagonal costs mean that any
        // path leaving |i-j| <= band must pay at least (2*(band+1)-|n-m|)*GAP.
        // A strictly cheaper in-band result is therefore globally optimal.
        // The margin covers Float accumulation error; ties/unproven cases widen.
        var band = minOf(maxOf(n,m), maxOf(24, abs(n-m)+8))
        var evaluated = 0L
        var path: Trace
        while (true) {
            path = trace(first,second,band,checkActive)
            evaluated += path.cells
            val outsideLowerBound = (2L*(band+1)-abs(n-m))*GAP
            if (band >= maxOf(n,m) || path.cost + 1f < outsideLowerBound) break
            band = if (band*2 >= maxOf(n,m)/2) maxOf(n,m) else band*2
        }
        val reversed = ArrayList<ComparisonColumn>(n + m)
        var i = n; var j = m
        var matches = 0
        var diverse = 0
        var lastMatch: ComparisonFrame? = null
        while (i > 0 || j > 0) {
            checkActive()
            when (path.direction(i,j)) {
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
        val shortCandidates = ArrayList<ComparisonZone>()
        var start = -1
        for (index in 0..columns.size) {
            if (index < columns.size && columns[index].different) {
                if (start < 0) start = index
            } else if (start >= 0) {
                val gap = (start until index).any { columns[it].first == null || columns[it].second == null }
                // One noisy boundary frame alone is inconclusive; actual
                // missing/extra content is retained even when it is short.
                if (gap || (index - start) * stepMs >= 512) zones.add(ComparisonZone(start, index))
                else if (stepMs == BASE_STEP_MS && start > 0 && index < columns.size &&
                    columns[start].first != null && columns[start].second != null
                ) shortCandidates.add(ComparisonZone(start, index))
                start = -1
            }
        }
        // Coarse PCM on device can put a masked syllable just ABOVE .85.
        // Admit only isolated borderline matches for the guarded 64-ms + PCM
        // second opinion. They are not zones until independently confirmed.
        if (stepMs == BASE_STEP_MS) {
            for (index in 1 until columns.lastIndex) {
                val current = columns[index]
                if (current.different || columns[index - 1].different ||
                    columns[index + 1].different) continue
                val a = current.first ?: continue
                val b = current.second ?: continue
                val score = similarity(first[a], second[b])
                if (score >= MATCH_THRESHOLD && score < .91f)
                    shortCandidates.add(ComparisonZone(index, index + 1))
            }
        }
        return AlignedAudioComparison(stepMs, first, second, columns, zones, evaluated,
            shortCandidates.distinct().sortedBy { it.start })
    }


    /** Read-only second opinion on a one-frame coarse mismatch. The active
     * 256-ms frame is surrounded by two 64-ms context frames on each side.
     * A single codec impulse is NOT a confirmed musical edit. Small alignment
     * shifts are admitted only when the unchanged context supports them.
     * The result is deliberately labelled uncertain and never authorizes trash.
     */
    internal fun confirmsShortDifference(
        first: List<ComparisonFrame>, second: List<ComparisonFrame>,
        coreFirst: Int, coreSecond: Int, coreFrames: Int,
    ): Boolean {
        if (coreFrames < 2 || first.isEmpty() || second.isEmpty()) return false
        var bestShift: Int? = null
        var bestContext = -1.0
        for (shift in -1..1) {
            val anchors = buildList {
                for (i in 0 until coreFirst) add(i)
                for (i in coreFirst + coreFrames until first.size) add(i)
            }.filter { i ->
                val j = i - coreFirst + coreSecond + shift
                j in second.indices && !first[i].silent && !second[j].silent
            }
            if (anchors.size < 2) continue
            val quality = anchors.map { i ->
                val j = i - coreFirst + coreSecond + shift
                similarity(first[i], second[j]).toDouble()
            }.average()
            if (quality > bestContext) { bestContext = quality; bestShift = shift }
        }
        if (bestContext < .90 || bestShift == null) return false
        var consecutive = 0
        var meaningfulMinimum = false
        for (i in coreFirst until coreFirst + coreFrames) {
            val j = i - coreFirst + coreSecond + bestShift
            if (i !in first.indices || j !in second.indices) return false
            val a = first[i]; val b = second[j]
            val similarity = similarity(a, b)
            if (a.silent || b.silent || !a.rms.isFinite() || !b.rms.isFinite() ||
                maxOf(a.rms, b.rms) < .00075f
            ) { consecutive = 0; continue }
            if (similarity < .87f) {
                if (similarity < .82f) meaningfulMinimum = true
                consecutive++
                if (consecutive >= 2 && meaningfulMinimum) return true
            } else consecutive = 0
        }
        return false
    }

    private class Trace(val band: Int, val width: Int, val directions: ByteArray,
        val cost: Float, val cells: Long) {
        fun direction(i: Int, j: Int): Int = directions[i*width+j-maxOf(0,i-band)].toInt()
    }

    /** Exact legacy scoring and tie order, with compact traceback for visited cells. */
    private fun trace(first: List<ComparisonFrame>, second: List<ComparisonFrame>, band: Int,
        checkActive: () -> Unit): Trace {
        val n=first.size; val m=second.size
        val width=minOf(m+1,2*band+1)
        val directions=ByteArray((n+1)*width)
        var previous=FloatArray(m+1){ Float.POSITIVE_INFINITY }
        var current=FloatArray(m+1){ Float.POSITIVE_INFINITY }
        var cells=0L
        for (j in 0..minOf(m,band)) { previous[j]=j*GAP; directions[j]=2 }
        for (i in 1..n) {
            checkActive()
            val low=maxOf(0,i-band); val high=minOf(m,i+band)
            if(low==0) { current[0]=i*GAP; directions[i*width]=1 }
            else current[low-1]=Float.POSITIVE_INFINITY
            if(high<m) current[high+1]=Float.POSITIVE_INFINITY
            for (j in maxOf(1,low)..high) {
                cells++
                val diagonal=previous[j-1]+(1f-similarity(first[i-1],second[j-1]))
                val delete=previous[j]+GAP
                val insert=current[j-1]+GAP
                val at=i*width+j-low
                when {
                    diagonal<=delete && diagonal<=insert -> {current[j]=diagonal;directions[at]=0}
                    delete<=insert -> {current[j]=delete;directions[at]=1}
                    else -> {current[j]=insert;directions[at]=2}
                }
            }
            val swap=previous;previous=current;current=swap
        }
        return Trace(band,width,directions,previous[m],cells)
    }

}
