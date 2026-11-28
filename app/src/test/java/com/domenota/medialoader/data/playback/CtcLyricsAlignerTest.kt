package com.domenota.medialoader.data.playback

import com.domenota.medialoader.data.playback.alignment.CtcLyricsAligner
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ln

class CtcLyricsAlignerTest {
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
