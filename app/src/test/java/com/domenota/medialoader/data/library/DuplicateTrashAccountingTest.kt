package com.domenota.medialoader.data.library

import org.junit.Assert.*
import org.junit.Test

class DuplicateTrashAccountingTest {
    @Test fun tenSelectedCannotBecomeAnEmptyReport(){
        val ids=(1..10).map{it.toString()}.toSet()
        val request=DuplicateTrashSelection(emptyList(),ids,emptySet(),emptyMap())
        assertEquals(ids,request.unaccounted(emptySet()))
        assertEquals(setOf("9","10"),request.unaccounted((1..8).map{it.toString()}.toSet()))
        assertTrue(request.unaccounted(ids).isEmpty())
    }
}
