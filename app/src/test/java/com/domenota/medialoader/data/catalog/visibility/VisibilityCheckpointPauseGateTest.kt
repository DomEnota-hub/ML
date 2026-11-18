package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class VisibilityCheckpointPauseGateTest {
    @Test fun aPauseCannotInterruptBeforeTheBatchCallsCheckpoint() {
        val gate=VisibilityCheckpointPauseGate()
        assertTrue(gate.request())
        assertFalse(gate.request())
        // The call site, not the request itself, decides whether it is safe.
        try { gate.checkpoint(); fail("Pause was ignored") }
        catch (_: VisibilityPausedAtCheckpoint) { }
        gate.checkpoint() // consumed exactly once
        assertTrue(gate.request())
        gate.reset()
        gate.checkpoint()
    }
}
