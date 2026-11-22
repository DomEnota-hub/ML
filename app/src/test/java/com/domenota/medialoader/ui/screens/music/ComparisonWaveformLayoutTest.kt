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

    @Test fun alignedCursorLookupUsesOriginalColumnPositions(){
        val columns=listOf(
            com.domenota.medialoader.data.library.ComparisonColumn(0,0,false),
            com.domenota.medialoader.data.library.ComparisonColumn(null,1,true),
            com.domenota.medialoader.data.library.ComparisonColumn(1,2,false),
            com.domenota.medialoader.data.library.ComparisonColumn(2,null,true))
        val (first,second)=comparisonCursorColumns(columns,3,3)
        assertArrayEquals(intArrayOf(0,2,3),first)
        assertArrayEquals(intArrayOf(0,1,2),second)
        assertEquals(0,comparisonSeekFrame(columns,1,true))
        assertEquals(1,comparisonSeekFrame(columns,1,false))
        assertEquals(2,comparisonSeekFrame(columns,3,true))
        assertEquals(2,comparisonSeekFrame(columns,3,false))
        assertNull(comparisonSeekFrame(columns,-1,true))
        assertNull(comparisonSeekFrame(emptyList(),0,true))
    }
    @Test fun cachedPeaksPreserveEveryTransientAndInvalidateByBinCount(){
        val values=listOf(.1f to .2f,.9f to .3f,.4f to .8f,.2f to 1f)
        val cache=ComparisonPeakCache(values,0..3)
        val two=cache.peaks(2)
        assertArrayEquals(floatArrayOf(.9f,.4f),two.first,0f)
        assertArrayEquals(floatArrayOf(.3f,1f),two.second,0f)
        assertSame(two.first,cache.peaks(2).first)
        val four=cache.peaks(4)
        assertArrayEquals(floatArrayOf(.1f,.9f,.4f,.2f),four.first,0f)
        assertNotSame(two.first,four.first)
    }
    @Test fun focusZoomHasFourBoundsAndPreservesEntireChange(){
        assertEquals(comparisonFocusRange(100,20,30,false),comparisonFocusRange(100,20,30,1))
        assertEquals(comparisonFocusRange(100,20,30,true),comparisonFocusRange(100,20,30,3))
        assertEquals(20..29,comparisonFocusRange(100,20,30,4))
        for(level in 1..4){
            val r=comparisonFocusRange(100,20,30,level)
            assertTrue(20 in r && 29 in r)
        }
    }


    @Test fun fourRecordingGroupCanSwitchTargetsWithoutChangingReference() {
        val ids = listOf("original", "censored", "alternative", "live")
        assertEquals(listOf("censored", "alternative", "live"), comparisonTargetIds(ids, "original"))
        val pair = comparisonPairWithReference(ids, "original", "censored", "original")
        assertEquals("original" to "censored", pair)
        assertEquals(listOf("original", "censored", "live"), comparisonTargetIds(ids, "alternative"))
    }

    @Test fun switchingReferencePreservesOtherTrackUnlessItBecomesTheNewReference() {
        val ids = listOf("A", "B", "C", "D")
        assertEquals("C" to "B", comparisonPairWithReference(ids, "A", "B", "C"))
        assertEquals("B" to "A", comparisonPairWithReference(ids, "A", "B", "B"))
        assertEquals("A" to "B", comparisonPairWithReference(ids, "A", "B", "A"))
        assertEquals("D" to "B", comparisonPairWithReference(ids, "A", "B", "D"))
    }

    @Test fun tenTrackGroupNeverSelfComparesOrAcceptsUnknownReferences() {
        val ids = (0 until 10).map { "rec-$it" }
        assertEquals(9, comparisonTargetIds(ids, "rec-0").size)
        assertTrue(comparisonTargetIds(ids, "rec-4").none { it == "rec-4" })
        assertEquals("rec-8" to "rec-0", comparisonPairWithReference(ids, "rec-0", "rec-8", "rec-8"))
        assertNull(comparisonPairWithReference(ids, "rec-0", "rec-1", "stranger"))
        assertNull(comparisonPairWithReference(ids, "rec-0", "rec-0", "rec-2"))
        assertNull(comparisonPairWithReference(listOf("only"), "only", "only", "only"))
    }

}
