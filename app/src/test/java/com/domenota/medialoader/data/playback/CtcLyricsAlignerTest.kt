package com.domenota.medialoader.data.playback

import com.domenota.medialoader.data.playback.alignment.CtcLyricsAligner
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ln

class CtcLyricsAlignerTest {
    @Test fun narrowForcedOnsetWithoutAnyAcousticLetterAnchorIsNotConfidence() {
        val p=peaks(33,1,33,2,33,3,33,4,33,5,33,0,33,11,33,7,33,8,33,9,33,10,33)
        assertNotNull(CtcLyricsAligner.align(p,arrayOf("абвгд","кжзий")){})
        // CER0.1 and a narrow conditional posterior cannot prove an absent initial е.
        assertNull(CtcLyricsAligner.align(p,arrayOf("абвгд","ежзий")){})
    }

    @Test fun emittedOnsetMustBelongToItsOwnPosteriorInterval() {
        val p=Array(80){t->
            val odds=if(t==1)1.5 else if(t in 50..61).995 else 1e-7
            val z=1+odds+32e-12
            FloatArray(34){v->ln(when(v){33->1/z;1->odds/z;else->1e-12/z}).toFloat()}
        }
        // Viterbi enters at40ms, but97% of valid paths enter around2s.
        assertNull(CtcLyricsAligner.align(p,arrayOf("а")){})
    }
    private fun peaks(vararg labels:Int)=Array(labels.size){t->FloatArray(34){v->ln(if(v==labels[t]) .99 else .01/33).toFloat()}}
    @Test fun repeatsRequireBlankAndPausesDoNotReceiveInterpolatedLines() {
        val result=CtcLyricsAligner.align(peaks(33,1,33,1,33,0,33,2,33),arrayOf("аа","б")){}
        assertArrayEquals(longArrayOf(40,280),requireNotNull(result).onsetsMs)
        assertNull(CtcLyricsAligner.align(peaks(1,1),arrayOf("аа")){})
    }
    @Test fun silenceAndUnrelatedTextDeclineInsteadOfProducingTiming() {
        assertNull(CtcLyricsAligner.align(peaks(33,33,33,33),arrayOf("а")){})
        assertNull(CtcLyricsAligner.align(peaks(33,1,33,2,33),arrayOf("я")){})
    }
    @Test fun unsupportedTextAndInvalidProbabilitiesDeclineAndCancellationPropagates() {
        assertNull(CtcLyricsAligner.align(peaks(1,33),arrayOf("hello")){})
        assertEquals("елка▁а",CtcLyricsAligner.normalize("Ёлка — А!"))
        val bad=peaks(1,33);bad[0][0]=Float.NaN
        assertNull(CtcLyricsAligner.align(bad,arrayOf("а")){})
        var propagated=false
        try{CtcLyricsAligner.align(peaks(1,33),arrayOf("а")){throw IllegalStateException("cancel")}}
        catch(_:IllegalStateException){propagated=true}
        assertTrue(propagated)
    }
    @Test fun longRecordingsOutsideTrellisBudgetDeclineBeforeAllocation() {
        val p=Array(15000){peaks(1)[0]}
        assertNull(CtcLyricsAligner.align(p,arrayOf("аб".repeat(3000))) {})
    }
}
