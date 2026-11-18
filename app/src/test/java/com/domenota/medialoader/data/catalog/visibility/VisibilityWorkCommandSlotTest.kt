package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class VisibilityWorkCommandSlotTest {
    private inline fun rejected(block:()->Unit) {
        try { block();fail("Overlapping work accepted") }
        catch (_: IllegalStateException) {}
    }
    @Test fun replayedOrForeignServiceIntentNeverStartsASecondTransfer() {
        val slot = VisibilityWorkCommandSlot()
        val request = slot.offer(VisibilityWorkCommand.SetHidden("folder",true))
        assertNull(slot.claim(request.id + 1))
        assertEquals(request,slot.claim(request.id))
        assertNull(slot.claim(request.id))
        rejected { slot.offer(VisibilityWorkCommand.ExplicitResume) }
        assertFalse(slot.finish(request.id + 1))
        assertTrue(slot.finish(request.id))
        assertFalse(slot.finish(request.id))
        assertFalse(slot.isBusy())
        assertNull(slot.claim(request.id))
    }
    @Test fun failedForegroundStartMayRevokeOnlyTheUnclaimedCommand() {
        val slot=VisibilityWorkCommandSlot()
        val first=slot.offer(VisibilityWorkCommand.SetHidden("folder",false))
        assertTrue(slot.cancelUnclaimed(first.id))
        assertNull(slot.claim(first.id))
        val next=slot.offer(VisibilityWorkCommand.ExplicitResume)
        assertTrue(next.id>first.id)
        assertEquals(next,slot.claim(next.id))
        assertFalse(slot.cancelUnclaimed(next.id))
        assertTrue(slot.finish(next.id))
    }
}
