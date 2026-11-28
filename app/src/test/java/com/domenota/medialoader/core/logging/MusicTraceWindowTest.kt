package com.domenota.medialoader.core.logging
import org.junit.Assert.*
import org.junit.Test
class MusicTraceWindowTest {
    @Test fun aggregatesRapidEventsAndResumesAtBoundary(){
        val w=MusicTraceWindow(500)
        assertEquals(0,w.admit(0))
        assertNull(w.admit(1))
        assertNull(w.admit(499))
        assertEquals(2,w.admit(500))
        assertEquals(0,w.admit(1000))
    }
}
