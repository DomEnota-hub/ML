package com.domenota.medialoader.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSessionRulesTest {
    @Test fun cancelledScanWithoutHistoryShowsNoFakeResults() {
        assertTrue(DiagnosticSessionRules.afterInterruptedRun(null).isEmpty())
    }

    @Test fun partialNewRunCannotOverwriteLastVerifiedResults() {
        val spec = DiagnosticCheckSpec("queue.snapshot", DiagnosticComponent.DOWNLOAD_QUEUE,
            DiagnosticImpact.REQUIRED)
        val previous = DiagnosticFinding.fromOutcome(spec,
            DiagnosticOutcome(DiagnosticState.ERROR, DiagnosticEvidence.VERIFIED,
                "Подтверждена проблема очереди.", "QUEUE_STALLED"), 1_050L, 10L)
        val completed = DiagnosticRun(DiagnosticMode.LOCAL, 1_000L, 1_100L, listOf(previous))
        val nextPartial = DiagnosticFinding.fromOutcome(spec,
            DiagnosticOutcome(DiagnosticState.OK, DiagnosticEvidence.VERIFIED,
                "Частичная проверка."), 1_250L, 10L)
        val stable = DiagnosticSessionRules.afterInterruptedRun(completed)
        assertEquals(listOf(previous), stable)
        assertEquals(DiagnosticState.ERROR, stable.single().state)
        assertTrue(nextPartial !in stable)
    }

    @Test fun completedOfflineScanKeepsNetworkSourceUnchecked() {
        val spec = DiagnosticCheckSpec("source.muzmo.http", DiagnosticComponent.SOURCES,
            DiagnosticImpact.OPTIONAL, DiagnosticScope.NETWORK)
        val skipped = DiagnosticFinding.notChecked(spec, "Сетевая проверка не выполнялась.")
        val run = DiagnosticRun(DiagnosticMode.LOCAL, 2_000L, 2_100L, listOf(skipped))
        assertEquals(DiagnosticState.UNCHECKED,
            DiagnosticSessionRules.afterInterruptedRun(run).single().state)
    }
}
