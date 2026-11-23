package com.domenota.medialoader.data.library

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

/** Synthetic signals only; private user recordings are not stored in CI. */
class AudioContinuousDifferenceTest {
    private fun synthetic(seconds: Int): FloatArray = FloatArray(seconds * 8000) { i ->
        val t = i.toDouble() / 8000.0
        (0.32 * sin(2 * PI * 203 * t) + 0.19 * sin(2 * PI * (349 * t + t*t*.07)) +
         0.13 * sin(2 * PI * 787 * t + .4 * sin(t))).toFloat()
    }
    private fun blocks(input: FloatArray): AudioPcmBlocks = AudioPcmBlocks().also { result ->
        input.forEach(result::append)
    }
    private fun alignment(a: FloatArray, b: FloatArray): AlignedAudioComparison {
        val firstCount = (a.size + 2047) / 2048
        val secondCount = (b.size + 2047) / 2048
        val first = List(firstCount) { ComparisonFrame(floatArrayOf(1f, 0f), .25f) }
        val second = List(secondCount) { ComparisonFrame(floatArrayOf(1f, 0f), .25f) }
        val cols = List(minOf(firstCount,secondCount)) { ComparisonColumn(it,it,false) }
        return AlignedAudioComparison(256, first, second, cols, emptyList())
    }

    @Test fun finds200msWordWithoutCoarseSpectralCandidate() {
        val a = synthetic(40); val b = a.copyOf()
        for (i in 31_232 * 8 until 31_424 * 8) b[i] *= .30f
        val raw = alignment(a,b)
        assertTrue(raw.zones.isEmpty())
        assertTrue(raw.shortCandidates.isEmpty())
        val z = AudioContinuousDifference.detect(blocks(a),blocks(b),raw)
        assertTrue("A masked word at 31s must not depend on coarse candidates",
            z.any { it.start * 256 <= 31_232 && it.endExclusive * 256 >= 31_424 })
    }

    @Test fun detectsRepeatedShortEditsAndDoesNotFillTheGaps() {
        val a = synthetic(46); val b = a.copyOf()
        val times = listOf(6_000, 12_000, 19_000, 26_000, 37_000)
        for (at in times) for (i in at * 8 until (at + 350) * 8) b[i] *= .22f
        val zones = AudioContinuousDifference.detect(blocks(a),blocks(b),alignment(a,b))
        assertEquals("Five separate edits, not a continuous unmatched song",times.size,zones.size)
        for (time in times) assertTrue(zones.any { it.start * 256 <= time && it.endExclusive * 256 >= time })
    }

    @Test fun doesNotFlagGainOffsetAndSingleCodecImpulse() {
        val a = synthetic(32)
        val shifted = FloatArray(a.size + 200) { i -> if (i < 200) 0f else a[i-200] * .68f }
        assertTrue(AudioContinuousDifference.detect(blocks(a),blocks(shifted),alignment(a,shifted)).isEmpty())
        val click = shifted.copyOf()
        for (i in 16 * 8000 + 200 until 16 * 8000 + 200 + 100) click[i] *= .1f
        assertTrue(AudioContinuousDifference.detect(blocks(a),blocks(click),alignment(a,click)).isEmpty())
    }

    @Test fun rejectsUnrelatedPairsWithoutInventingAComparison() {
        val a = synthetic(30)
        val b = FloatArray(a.size) { i -> (0.4 * sin(2 * PI * 631 * i / 8000.0)).toFloat() }
        assertTrue(AudioContinuousDifference.detect(blocks(a),blocks(b),alignment(a,b)).isEmpty())
    }

    @Test fun keepsLaterSongAfterMiddleInsertionAndStillFindsAnotherEdit() {
        val a = synthetic(90)
        val insertAt = 30 * 8000
        val insertSamples = 3200 // 400 ms; coarse alignment rounds it to 512 ms
        val b = FloatArray(a.size + insertSamples) { i ->
            when {
                i < insertAt -> a[i]
                i < insertAt + insertSamples -> 0f
                else -> a[i-insertSamples]
            }
        }
        for (i in 73 * 8000 + insertSamples until 73 * 8000 + insertSamples + 2400) b[i] *= .25f
        val firstCount = (a.size + 2047) / 2048
        val secondCount = (b.size + 2047) / 2048
        val first = List(firstCount){ComparisonFrame(floatArrayOf(1f),.3f)}
        val second = List(secondCount){ComparisonFrame(floatArrayOf(1f),.3f)}
        val cols = ArrayList<ComparisonColumn>()
        for (i in 0 until firstCount) {
            if (i == 117) {cols.add(ComparisonColumn(null,117,true));cols.add(ComparisonColumn(null,118,true))}
            cols.add(ComparisonColumn(i, i + if(i>=117)2 else 0, false))
        }
        val aligned=AlignedAudioComparison(256,first,second,cols,listOf(ComparisonZone(117,119)))
        val z=AudioContinuousDifference.detect(blocks(a),blocks(b),aligned)
        assertTrue("The later edit must survive a 400ms insertion",z.any {
            val frame = cols[it.start].first ?: return@any false
            frame * 256 in 72_000..74_000
        })
        assertTrue("The rest of the song must not be one huge difference",z.none {
            it.endExclusive - it.start > 8
        })
    }
}
