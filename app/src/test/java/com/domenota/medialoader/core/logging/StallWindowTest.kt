package com.domenota.medialoader.core.logging

import org.junit.Assert.*
import org.junit.Test

class StallWindowTest {
    @Test fun shortDelaysAreIgnoredAndLongStallHasAtMostThreeSamples() {
        val stall = StallWindow()
        assertFalse(stall.shouldReport(699))
        assertTrue(stall.shouldReport(750))
        assertFalse(stall.shouldReport(1000))
        assertTrue(stall.shouldReport(2750))
        assertTrue(stall.shouldReport(4750))
        assertFalse(stall.shouldReport(9000))
    }
    @Test fun recoveryAllowsNextStallToBeReported() {
        val stall = StallWindow()
        assertTrue(stall.shouldReport(750))
        stall.reset()
        assertTrue(stall.shouldReport(750))
    }
}
