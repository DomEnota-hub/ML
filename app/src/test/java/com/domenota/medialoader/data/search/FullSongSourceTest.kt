package com.domenota.medialoader.data.search

import org.junit.Assert.assertEquals
import org.junit.Test

class FullSongSourceTest {
    @Test fun defaultsAreStableAndFailClosed() {
        assertEquals(FullSongSource.HITMOZ, FullSongSource.fromStored(null))
        assertEquals(FullSongSource.HITMOZ, FullSongSource.fromStored("unknown"))
        assertEquals(FullSongSource.MUZMO, FullSongSource.fromStored("muzmo"))
        assertEquals(FullSongSource.AUTO, FullSongSource.fromStored("auto"))
    }
}
