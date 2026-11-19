package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class VisibilityBatchTimingSummaryTest {
    @Test fun oneEightAndFortySixTimingsAreDeterministic() {
        assertEquals(VisibilityBatchTimingSummary(1,137,137,137),
            visibilityBatchTimingSummary(listOf(137L)))
        assertEquals(VisibilityBatchTimingSummary(8,4,8,8),
            visibilityBatchTimingSummary(listOf(8,2,5,1,7,4,6,3).map(Int::toLong)))
        assertEquals(VisibilityBatchTimingSummary(46,23,42,44),
            visibilityBatchTimingSummary((1L..46L).toList()))
    }
    @Test fun invalidOrIncompleteSamplesAreRejected() {
        assertEquals(VisibilityBatchTimingSummary(5,10,3000,3000),
            visibilityBatchTimingSummary(listOf(900,3,3000,1,10).map(Int::toLong)))
        assertTrue(runCatching{visibilityBatchTimingSummary(emptyList())}.isFailure)
        assertTrue(runCatching{visibilityBatchTimingSummary(listOf(-1L))}.isFailure)
        assertTrue(runCatching{
            visibilityBatchTimingSummary(List(VisibilityBatchRecord.MAX_MEMBERS+1){0L})
        }.isFailure)
    }
}
