package com.domenota.medialoader.ui.screens.music

import org.junit.Assert.*
import org.junit.Test

class DuplicateReferenceKeyTest {
    @Test fun distinctButEqualPayloadsNeverTriggerStructuralEquality() {
        val a = listOf(1, 2, 3)
        val b = listOf(1, 2, 3)
        assertEquals(a, b)
        assertEquals(DuplicateReferenceKey(a), DuplicateReferenceKey(a))
        assertNotEquals(DuplicateReferenceKey(a), DuplicateReferenceKey(b))
        assertNotEquals(DuplicateReferenceKey(a), DuplicateReferenceKey(null))
    }
}
