package com.domenota.medialoader.data.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedFileStatusCacheTest {
    @Test fun resumesReuseStatusButOpeningFileAlwaysChecksAgain() {
        var now = 0L
        var calls = 0
        var exists = true
        val cache = SavedFileStatusCache({ calls++; if (exists) SavedFileStatus.PRESENT else SavedFileStatus.MISSING }, { now })
        assertEquals(SavedFileStatus.PRESENT, cache.get("file"))
        exists = false
        now = 59_999L
        assertEquals(SavedFileStatus.PRESENT, cache.get("file"))
        assertEquals(1, calls)
        assertEquals(SavedFileStatus.MISSING, cache.get("file", fresh = true))
        assertEquals(2, calls)
        now += 60_000L
        cache.get("file")
        assertEquals(3, calls)
    }
}
