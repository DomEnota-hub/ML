package com.domenota.medialoader.data.playback

import com.domenota.medialoader.data.playback.alignment.AlignmentServiceRunSlot
import org.junit.Assert.*
import org.junit.Test

class AlignmentServiceRunSlotTest {
    @Test fun staleStartCannotStealOwnershipAndOldFinishCannotStopNewRequest() {
        val slot=AlignmentServiceRunSlot()
        assertTrue(slot.claim("a",1,"a"))
        assertFalse(slot.claim("stale",2,"a"));assertTrue(slot.owns("a"))
        assertTrue(slot.claim("b",3,"b"))
        assertNull(slot.finish("a"));assertTrue(slot.owns("b"))
        assertEquals(3,slot.finish("b"));assertFalse(slot.hasOwner())
    }
    @Test fun duplicateStartDoesNotCreateAnotherExecutionAndLatestStartIdControlsShutdown() {
        val slot=AlignmentServiceRunSlot()
        assertTrue(slot.claim("a",10,"a"));assertFalse(slot.claim("a",11,"a"))
        assertTrue(slot.owns("a"));assertEquals(11,slot.finish("a"));assertNull(slot.finish("a"))
    }
}
