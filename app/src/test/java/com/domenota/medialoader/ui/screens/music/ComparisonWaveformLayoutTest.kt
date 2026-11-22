package com.domenota.medialoader.ui.screens.music

import org.junit.Assert.*
import org.junit.Test

class ComparisonWaveformLayoutTest {
    @Test fun focusAlwaysIncludesWholeSelectedZoneAndClipsToRecording(){
        assertEquals(0..11,comparisonFocusRange(200,0,4,false))
        assertEquals(194..199,comparisonFocusRange(200,196,200,true))
        assertEquals(12..37,comparisonFocusRange(100,20,30,false))
        assertEquals(18..31,comparisonFocusRange(100,20,30,true))
        assertTrue(comparisonFocusRange(0,0,1,false).isEmpty())
    }
    @Test fun binsCoverAllAndOnlyActualColumnsWithoutGaps(){
        for(count in listOf(1,4,21,1800))for(bins in listOf(1,minOf(19,count),count)){
            val actual=(0 until bins).flatMap{comparisonBucket(7,count,bins,it).toList()}
            assertEquals((7 until 7+count).toList(),actual)
        }
    }
}
