package com.domenota.medialoader.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticLabelsTest {
    @Test fun titlesDoNotLeakInternalMusicSourceNamesOrOpaqueCodes() {
        for (source in DiagnosticSource.entries) {
            val spec = DiagnosticCheckSpec("source.${source.id}.http",
                DiagnosticComponent.SOURCES, DiagnosticImpact.OPTIONAL, DiagnosticScope.NETWORK)
            val label = DiagnosticLabels.check(spec)
            assertTrue(label.contains(source.label))
            assertFalse(label.contains("source."))
        }
        val primary = DiagnosticLabels.check(DiagnosticCheckSpec(
            "source.hitmoz.http", DiagnosticComponent.SOURCES, DiagnosticImpact.OPTIONAL))
        val backup = DiagnosticLabels.check(DiagnosticCheckSpec(
            "source.muzmo.http", DiagnosticComponent.SOURCES, DiagnosticImpact.OPTIONAL))
        assertFalse(primary.contains("hitmoz", ignoreCase = true))
        assertFalse(backup.contains("muzmo", ignoreCase = true))
    }

    @Test fun uncheckedAndUnverifiableResultsHaveDistinctMessages() {
        assertEquals("Не проверено", DiagnosticLabels.state(DiagnosticState.UNCHECKED))
        assertEquals("Не удалось подтвердить",
            DiagnosticLabels.state(DiagnosticState.UNAVAILABLE))
        assertEquals("Предупреждение", DiagnosticLabels.state(DiagnosticState.WARNING))
    }

    @Test fun limitedProbeAndProbeExecutionFailureHaveDifferentLabels() {
        val spec = DiagnosticCheckSpec("source.muzmo.http", DiagnosticComponent.SOURCES,
            DiagnosticImpact.OPTIONAL, DiagnosticScope.NETWORK)
        val blocked = DiagnosticFinding.fromOutcome(spec, SourceDiagnosticRules.http(403), 1_000, 5)
        val timedOut = DiagnosticFinding.fromOutcome(spec,
            DiagnosticOutcome.unavailable("Срок истёк", "CHECK_TIMEOUT"), 1_000, 12)
        assertEquals("Проверка ограничена", DiagnosticLabels.finding(blocked))
        assertEquals("Не удалось выполнить", DiagnosticLabels.finding(timedOut))
    }
}
