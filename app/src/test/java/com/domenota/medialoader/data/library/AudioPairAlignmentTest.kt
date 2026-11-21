package com.domenota.medialoader.data.library

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin
import kotlin.math.PI

class AudioPairAlignmentTest {
    // Distinct spectral frames with enough variation to establish an alignment.
    private fun recording(n:Int):List<ComparisonFrame> = List(n){ index ->
        val v=FloatArray(128)
        v[index%128]=1f
        ComparisonFrame(v,.5f)
    }
    @Test fun oneSecondLongerIntroDoesNotMarkRemainingRecording() {
        val original=recording(80)
        val intro=List(4){i->ComparisonFrame(FloatArray(128).apply{this[100+i]=1f},.4f)}
        val result=requireNotNull(AudioPairAlignment.align(original,intro+original,256))
        assertEquals(listOf(ComparisonZone(0,4)),result.zones)
        assertEquals(4,result.columns.first{it.first==0}.second)
        assertTrue(result.columns.drop(4).none{it.different})
    }
    @Test fun missingMiddleSectionRealignsLaterFrames() {
        val original=recording(90)
        val short=original.take(30)+original.drop(38)
        val result=requireNotNull(AudioPairAlignment.align(original,short,256))
        assertEquals(listOf(ComparisonZone(30,38)),result.zones)
        assertEquals(30,result.columns.first{it.first==38}.second)
    }
    @Test fun differentLevelDoesNotChangeNormalizedSpectrum() {
        val power=DoubleArray(64){(it+1)*(it+1).toDouble()}
        val a=AudioPairAlignment.normalizedSpectrum(power)
        val b=AudioPairAlignment.normalizedSpectrum(DoubleArray(64){power[it]*.04})
        assertArrayEquals(a,b,.00001f)
    }
    @Test fun unrelatedOrStationarySpectraDoNotInventConfirmedAlignment() {
        val first=recording(60)
        val unrelated=List(60){ComparisonFrame(FloatArray(128).apply{this[64+it]=1f},.5f)}
        assertNull(AudioPairAlignment.align(first,unrelated,256))
        val tone=List(80){first.first()}
        assertNull(AudioPairAlignment.align(tone,tone,256))
    }
    @Test fun insertedFrameAndRemovedFrameAreBothLocalized() {
        val first=recording(90)
        val extra=ComparisonFrame(FloatArray(128).apply{this[120]=1f},.5f)
        val second=first.take(20)+listOf(extra)+first.subList(20,50)+first.drop(51)
        val result=requireNotNull(AudioPairAlignment.align(first,second,256))
        assertEquals(2,result.zones.size)
        assertEquals(20,result.columns.first{it.first==20}.second?.minus(1))
        assertEquals(51,result.columns.first{it.first==51}.second)
    }
    @Test fun gainChangeOnDecodedSpectralFramesIsNotAnAcousticChange() {
        fun frames(gain:Float):List<ComparisonFrame>{
            val b=AcousticFingerprints.ComparisonBuilder(256){}
            repeat(8000*12){i->
                val hz=220+(i/2048)%17*93
                b.accept((gain*sin(2*PI*hz*i/8000)).toFloat())
            }
            return b.finish()
        }
        val result=requireNotNull(AudioPairAlignment.align(frames(.6f),frames(.12f),256))
        assertTrue(result.zones.isEmpty())
    }

    @Test fun actualPcmOneSecondPrefixIsNotPaintedRedAcrossEntireTrack() {
        fun frames(prefix:Boolean):List<ComparisonFrame>{
            val builder=AcousticFingerprints.ComparisonBuilder(256){}
            if(prefix)repeat(8000){i->builder.accept((.6*sin(2*PI*1800*i/8000)).toFloat())}
            repeat(8000*18){i->
                val hz=220+(i/2048)%17*93
                builder.accept((.6*sin(2*PI*hz*i/8000)).toFloat())
            }
            return builder.finish()
        }
        val result=requireNotNull(AudioPairAlignment.align(frames(false),frames(true),256))
        assertTrue(result.zones.isNotEmpty())
        assertTrue(result.zones.first().start<=1)
        assertTrue(result.zones.first().endExclusive in 3..6)
        assertTrue(result.columns.drop(6).count{it.different}<=3)
    }
    @Test fun distributedSmallDriftDoesNotMarkResamplingGapsAsEdits() {
        val first=recording(240)
        val second=buildList{
            first.forEachIndexed{i,frame->add(frame);if(i in setOf(39,79,119,159,199))add(frame)}
        }
        val result=requireNotNull(AudioPairAlignment.align(first,second,256))
        assertTrue(result.zones.isEmpty())
    }
}
