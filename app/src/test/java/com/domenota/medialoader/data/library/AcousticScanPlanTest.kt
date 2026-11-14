package com.domenota.medialoader.data.library
import org.junit.Assert.*
import org.junit.Test
class AcousticScanPlanTest {
    @Test fun shortUnknownAndExpandedAreComplete(){
        listOf(null,0L,12_000L,45_000L).forEach{assertEquals(listOf(AcousticWindow(0,null)),AcousticScanPlan.windows(it))}
        assertEquals(listOf(AcousticWindow(0,null)),AcousticScanPlan.windows(240_000,true))
    }
    @Test fun ordinaryAndLongTracksHaveDistributedBoundedWindows(){
        for((duration,count) in listOf(60_000L to 2,240_000L to 3,900_000L to 5)){
            val windows=AcousticScanPlan.windows(duration)
            assertEquals(count,windows.size);assertEquals(0L,windows.first().startMs)
            assertEquals(duration,windows.last().endMs)
            assertTrue(windows.all{it.endMs!!-it.startMs==15_000L})
            assertTrue(windows.zipWithNext().all{(a,b)->a.endMs!!<=b.startMs})
        }
    }
    @Test fun windowsRetainAbsoluteLandmarkTimesWithoutCrossGapPairs(){
        val b=AcousticFingerprints.Builder()
        fun feed(){repeat(8000*10){i->b.accept(kotlin.math.sin(i*.18).toFloat()+kotlin.math.sin(i*.42).toFloat())}}
        b.beginWindow(0);feed();b.beginWindow(120_000);feed()
        val marks=b.finish().landmarks
        assertTrue(marks.isNotEmpty())
        assertTrue(marks.any{it.frame>=3750})
        assertTrue(marks.none{it.frame in 313..3749})
    }
}
