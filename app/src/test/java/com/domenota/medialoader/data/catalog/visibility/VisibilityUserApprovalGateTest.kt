package com.domenota.medialoader.data.catalog.visibility

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class VisibilityUserApprovalGateTest {
    @Test fun onlyTheMatchingClaimedActivityCanResolveTheTicket() = runBlocking {
        val gate = VisibilityUserApprovalGate<String>(1_000L)
        val job = async { gate.request("pending-intent-reference") }
        val ticket = withTimeout(1_000L) { gate.pending.first { it != null }!! }
        assertFalse(gate.decide(ticket.id + 1, true))
        assertFalse(gate.decide(ticket.id, true)) // Not yet claimed by a visible Activity
        assertTrue(gate.claim(ticket.id))
        assertFalse(gate.claim(ticket.id))
        assertTrue(gate.decide(ticket.id, false))
        assertFalse(job.await())
        assertNull(gate.pending.value)
        assertFalse(gate.decide(ticket.id, true))
    }

    @Test fun activityDisappearanceRejectsAndDoesNotFakeAnApproval() = runBlocking {
        val gate = VisibilityUserApprovalGate<String>(1_000L)
        val job = async { gate.request("permission") }
        val ticket = withTimeout(1_000L) { gate.pending.first { it != null }!! }
        assertTrue(gate.claim(ticket.id))
        assertTrue(gate.rejectClaimed(ticket.id))
        assertFalse(job.await())
        assertNull(gate.pending.value)
    }

    @Test fun processCancellationCannotLeaveAnOrphanConsentTicket() = runBlocking {
        val gate = VisibilityUserApprovalGate<String>(1_000L)
        val job = async { gate.request("pending") }
        withTimeout(1_000L) { gate.pending.first { it != null } }
        job.cancelAndJoin()
        assertNull(gate.pending.value)
        assertTrue(gate.pending.value == null)
    }

    @Test fun unclaimedRequestTimesOutWithoutDeletingOrApprovingAnything() = runBlocking {
        val gate = VisibilityUserApprovalGate<String>(25L)
        try { gate.request("no-activity"); fail("Expected timeout") }
        catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
        assertNull(gate.pending.value)
    }

    @Test fun onlyOneRequestCanBeInFlight() = runBlocking {
        val gate = VisibilityUserApprovalGate<String>(1_000L)
        val pending = async { gate.request("first") }
        val ticket = withTimeout(1_000L) { gate.pending.first { it != null }!! }
        try { gate.request("second"); fail("Overlapping permission request accepted") }
        catch (_: IllegalStateException) { }
        assertTrue(gate.claim(ticket.id))
        assertTrue(gate.decide(ticket.id, true))
        assertTrue(pending.await())
        assertNull(gate.pending.value)
    }
}
