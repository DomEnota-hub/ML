package com.domenota.medialoader.core.logging

import org.junit.Assert.*
import org.junit.Test

class LibraryWorkCountersTest {
    @Test fun countersAreBoundedAndSnapshotDoesNotResetOrMutate() {
        val counters = LibraryWorkCounterSet()
        repeat(500) { counters.increment(LibraryWork.COMPOSITION) }
        assertEquals(500L, counters.snapshot().getValue(LibraryWork.COMPOSITION))
        assertEquals(0L, counters.snapshot().getValue(LibraryWork.MEDIASTORE_QUERY))
        counters.increment(LibraryWork.MEDIASTORE_QUERY)
        assertEquals(1L, counters.snapshot().getValue(LibraryWork.MEDIASTORE_QUERY))
        assertEquals(LibraryWork.entries.size, counters.snapshot().size)
    }
}
