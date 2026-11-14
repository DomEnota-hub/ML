package com.domenota.medialoader.data.library
import org.junit.Assert.*
import org.junit.Test
class DuplicateProgressPolicyTest {
    @Test fun etaNeedsMeasuredWork(){
        assertNull(DuplicateProgressPolicy.etaMs(1,100,10000))
        assertNull(DuplicateProgressPolicy.etaMs(10,100,100))
        assertEquals(90_000L,DuplicateProgressPolicy.etaMs(10,100,10000))
    }
    @Test fun scanShowsCurrentMeasuredPhaseAndNoInventedGroupingPercentage(){
        assertEquals(1f,DuplicateProgressPolicy.fraction(DuplicateScanPhase.METADATA,100,100,true)!!,.001f)
        assertEquals(1f,DuplicateProgressPolicy.fraction(DuplicateScanPhase.ACOUSTIC,100,100,true)!!,.001f)
        assertNull(DuplicateProgressPolicy.fraction(DuplicateScanPhase.GROUPING,100,100,true))
        assertNull(DuplicateProgressPolicy.fraction(DuplicateScanPhase.SAVING,100,100,true))
    }
}
