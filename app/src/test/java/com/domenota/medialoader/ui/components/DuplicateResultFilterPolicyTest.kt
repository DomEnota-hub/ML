package com.domenota.medialoader.ui.components

import org.junit.Assert.*
import org.junit.Test

class DuplicateResultFilterPolicyTest {
    private enum class Kind { EXACT, ACOUSTIC, VERSION }

    @Test fun emptyCountsHideAllFilters() {
        assertFalse(DuplicateResultFilterPolicy.hasResults(emptyMap<Kind,Int>()))
        assertEquals(emptyList<Kind>(),
            DuplicateResultFilterPolicy.available(Kind.entries,emptyMap()))
    }

    @Test fun populatedKindsRemainInDeclaredOrder() {
        val counts=mapOf(Kind.VERSION to 2,Kind.EXACT to 3)
        assertEquals(listOf(Kind.EXACT,Kind.VERSION),
            DuplicateResultFilterPolicy.available(Kind.entries,counts))
    }

    @Test fun disappearingSelectionFallsBackToAll() {
        assertNull(DuplicateResultFilterPolicy.active(Kind.ACOUSTIC,
            mapOf(Kind.EXACT to 2)))
    }

    @Test fun positiveSelectionIsKept() {
        assertEquals(Kind.EXACT,DuplicateResultFilterPolicy.active(Kind.EXACT,
            mapOf(Kind.EXACT to 3,Kind.VERSION to 0)))
    }

    @Test fun countsIncludeOnlyPresentKinds() {
        val counts=DuplicateResultFilterPolicy.counts(
            listOf(Kind.EXACT,Kind.VERSION,Kind.EXACT))
        assertEquals(2,counts[Kind.EXACT])
        assertEquals(1,counts[Kind.VERSION])
        assertFalse(counts.containsKey(Kind.ACOUSTIC))
        assertTrue(DuplicateResultFilterPolicy.hasResults(counts))
    }
}
