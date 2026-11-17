package com.domenota.medialoader.data.catalog.visibility

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VisibilityServiceCancellationTest {
    @Test fun cancelledServiceAlwaysReleasesTheInProcessReservation() = runBlocking {
        val slot=VisibilityWorkCommandSlot()
        val request=slot.offer(VisibilityWorkCommand.ExplicitResume)
        assertEquals(request,slot.claim(request.id))
        val entered=CompletableDeferred<Unit>()
        val worker=launch {
            val observer=launch { awaitCancellation() }
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                releaseVisibilityRequestAfterCancellation(observer) {
                    assertTrue(slot.finish(request.id))
                }
            }
        }
        entered.await()
        worker.cancelAndJoin()
        assertFalse(slot.isBusy())
        val next=slot.offer(VisibilityWorkCommand.ExplicitResume)
        assertTrue(next.id>request.id)
        assertEquals(next,slot.claim(next.id))
        assertTrue(slot.finish(next.id))
    }

    @Test fun completedObserverReleasesTheReservationToo() = runBlocking {
        val slot=VisibilityWorkCommandSlot()
        val request=slot.offer(VisibilityWorkCommand.ExplicitResume)
        slot.claim(request.id)
        val observer=launch { }
        observer.join()
        releaseVisibilityRequestAfterCancellation(observer) {
            assertTrue(slot.finish(request.id))
        }
        assertFalse(slot.isBusy())
    }
}
