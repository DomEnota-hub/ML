package com.domenota.medialoader.data.library
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import java.util.Random
class AcousticPerformanceRegressionTest {
    private fun samples(seconds:Int,seed:Long):FloatArray{
        val r=Random(seed)
        val tones=DoubleArray(seconds*4+1){160+r.nextDouble()*2800}
        return FloatArray(seconds*8000){i->val t=i/8000.0;val f=tones[(t*4).toInt()]
            (.35*sin(2*PI*f*t)+.2*sin(2*PI*f*1.4*t)+.12*sin(2*PI*tones[((t*4).toInt()+7)%tones.size]*t)).toFloat()}
    }
    @Test fun optimizedFullFingerprintMatchesPinnedBaseline(){
        val input=samples(32,17)
        val old=BaselineAcousticFingerprints.Builder();val fresh=AcousticFingerprints.Builder()
        val before=System.nanoTime();input.forEach(old::accept);val expected=old.finish();val baselineNanos=System.nanoTime()-before
        val start=System.nanoTime();input.forEach(fresh::accept);val actual=fresh.finish();val newNanos=System.nanoTime()-start
        assertEquals(expected.landmarks.map{it.hash to it.frame}.toSet(),actual.landmarks.map{it.hash to it.frame}.toSet())
        println("ML_ACOUSTIC_BENCHMARK 32s synthetic baselineMs=${baselineNanos/1_000_000} optimizedMs=${newNanos/1_000_000} landmarks=${actual.landmarks.size}")
    }
    @Test fun expandedModeCanCompareMaterialOutsideDistributedWindows(){
        val input=samples(120,81)
        fun build(samples:FloatArray)=AcousticFingerprints.Builder().also{b->samples.forEach(b::accept)}.finish()
        val full=build(input)
        val trimmed=build(input.copyOfRange(25*8000,43*8000))
        assertTrue(AcousticFingerprints.matches(full,trimmed))
        val sampled=AcousticFingerprints.Builder()
        AcousticScanPlan.windows(120_000).forEach{w->
            sampled.beginWindow(w.startMs)
            input.copyOfRange((w.startMs*8).toInt(),(w.endMs!!*8).toInt()).forEach(sampled::accept)
        }
        assertTrue(sampled.finish().landmarks.size<=6000)
        assertTrue(AcousticScanPlan.windows(120_000,true).single().endMs==null)
    }
    @Test fun compactIndexPreservesCandidatesAndCommonHashCap(){
        val fp=AcousticFingerprint(packedAcousticLandmarks((0 until 110).map{AcousticLandmark(it,it*8)}))
        assertEquals(setOf("a" to "b"),AcousticFingerprints.candidatePairs(mapOf("b" to fp,"a" to fp)))
        assertTrue(AcousticFingerprints.candidatePairs((0..40).associate{"t$it" to fp}).isEmpty())
        assertEquals(AcousticAlignment(0,0),AcousticFingerprints.alignment(fp,fp))
    }
}
