package com.domenota.medialoader.ui.screens.music

import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateWaveformComparisonProgressTest {
    @Test fun unknownOrZeroDurationNeverIntroducesFakeMatchMarker() {
        assertEquals(0f,comparisonWaveformProgress(1200L,null),0f)
        assertEquals(0f,comparisonWaveformProgress(1200L,0L),0f)
        assertEquals(0f,comparisonWaveformProgress(1200L,-1L),0f)
    }

    @Test fun knownDurationKeepsBothReferenceAndTargetPositionsWithinTheirOwnTrack() {
        assertEquals(.25f,comparisonWaveformProgress(15000L,60000L),.00001f)
        assertEquals(.50f,comparisonWaveformProgress(30000L,60000L),.00001f)
        assertEquals(0f,comparisonWaveformProgress(-30000L,60000L),0f)
        assertEquals(1f,comparisonWaveformProgress(90000L,60000L),0f)
    }
}
