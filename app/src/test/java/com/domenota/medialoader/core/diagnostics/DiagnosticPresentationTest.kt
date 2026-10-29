package com.domenota.medialoader.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPresentationTest {
    private val first = DiagnosticCheckSpec("queue.first", DiagnosticComponent.DOWNLOAD_QUEUE,
        DiagnosticImpact.REQUIRED, maxAgeMillis = 1_000)
    private val second = DiagnosticCheckSpec("source.second", DiagnosticComponent.SOURCES,
        DiagnosticImpact.OPTIONAL, maxAgeMillis = 1_000)
    private val specs = listOf(first, second)
    private val result = DiagnosticFinding.fromOutcome(first,
        DiagnosticOutcome(DiagnosticState.OK, DiagnosticEvidence.VERIFIED, "Проверено"),
        checkedAtMillis = 2_000, durationMillis = 3)

    @Test fun currentRunFindingsCannotBeStaleAgainstOldScreenClock() {
        assertFalse(DiagnosticPresentation.isExpired(result, running = true, nowMillis = 1_000))
        val summary = DiagnosticAssessment.application(specs, listOf(result), 1_000,
            currentRun = true)
        assertEquals(1, summary.freshResults)
        assertEquals(0, summary.expiredResults)
        assertEquals(1, DiagnosticPresentation.confirmedGoodCount(listOf(result)))
    }

    @Test fun onlyNextUnfinishedCheckIsActive() {
        assertEquals(second.code, DiagnosticPresentation.activeCode(specs, listOf(result), true))
        assertTrue(DiagnosticPresentation.isActive(second, specs, listOf(result), true))
        assertFalse(DiagnosticPresentation.isActive(first, specs, listOf(result), true))
        assertEquals(null, DiagnosticPresentation.activeCode(specs, listOf(result), false))
    }
}
