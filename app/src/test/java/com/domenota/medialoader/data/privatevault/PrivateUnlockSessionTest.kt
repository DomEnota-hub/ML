package com.domenota.medialoader.data.privatevault

import org.junit.Assert.*
import org.junit.Test

class PrivateUnlockSessionTest {
    private var now = 10_000L
    private val session = PrivateUnlockSession { now }

    @Test fun defaultAndNewProcessNeverRetainAccess() {
        assertFalse(session.consume())
        session.suspend(PrivateUnlockDuration.ON_EXIT, true)
        assertFalse(session.consume())
        session.suspend(PrivateUnlockDuration.UNTIL_PROCESS_END, true)
        assertFalse(PrivateUnlockSession { now }.consume())
        assertTrue(session.consume())
    }

    @Test fun everyTimedOptionUsesExactMonotonicBoundaryAndOneShotPermit() {
        val options = mapOf(
            PrivateUnlockDuration.ONE_MINUTE to 60_000L,
            PrivateUnlockDuration.FIVE_MINUTES to 300_000L,
            PrivateUnlockDuration.FIFTEEN_MINUTES to 900_000L,
            PrivateUnlockDuration.THIRTY_MINUTES to 1_800_000L,
            PrivateUnlockDuration.SIXTY_MINUTES to 3_600_000L,
        )
        for ((duration, milliseconds) in options) {
            assertEquals(milliseconds, duration.millis)
            session.suspend(duration, true)
            now += milliseconds - 1
            assertTrue(duration.name, session.consume())
            assertFalse(session.consume())
            session.suspend(duration, true)
            now += milliseconds
            assertFalse(duration.name, session.consume())
        }
    }

    @Test fun duplicateExitAndBackgroundEventsNeverExtendPermit() {
        session.suspend(PrivateUnlockDuration.ONE_MINUTE, true)
        now += 50_000
        session.suspend(PrivateUnlockDuration.SIXTY_MINUTES, false)
        now += 10_000
        assertFalse(session.consume())
    }

    @Test fun processLifetimeStillRevokesOnManualLockOrClockRegression() {
        session.suspend(PrivateUnlockDuration.UNTIL_PROCESS_END, true)
        now += 86_400_000
        assertTrue(session.consume())
        session.suspend(PrivateUnlockDuration.UNTIL_PROCESS_END, true)
        session.clear()
        assertFalse(session.consume())
        session.suspend(PrivateUnlockDuration.ONE_MINUTE, true)
        now--
        assertFalse(session.consume())
    }
}
