package com.domenota.medialoader.data.diagnostics

import com.domenota.medialoader.core.diagnostics.DiagnosticCheckSpec
import com.domenota.medialoader.core.diagnostics.DiagnosticComponent
import com.domenota.medialoader.core.diagnostics.DiagnosticCoordinator
import com.domenota.medialoader.core.diagnostics.DiagnosticEvidence
import com.domenota.medialoader.core.diagnostics.DiagnosticFinding
import com.domenota.medialoader.core.diagnostics.DiagnosticImpact
import com.domenota.medialoader.core.diagnostics.DiagnosticMode
import com.domenota.medialoader.core.diagnostics.DiagnosticOutcome
import com.domenota.medialoader.core.diagnostics.DiagnosticProbe
import com.domenota.medialoader.core.diagnostics.DiagnosticRun
import com.domenota.medialoader.core.diagnostics.DiagnosticScope
import com.domenota.medialoader.core.diagnostics.DiagnosticState
import com.domenota.medialoader.core.diagnostics.FaultJournal
import com.domenota.medialoader.core.diagnostics.FaultObservation
import com.domenota.medialoader.core.diagnostics.FaultRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticJournalRunnerTest {
    private class MemoryStore : FaultHistoryStore {
        var stored = emptyList<FaultRecord>()
        var runWrites = 0
        override suspend fun entries() = stored
        override suspend fun record(run: DiagnosticRun): List<FaultRecord> {
            runWrites++
            stored = FaultJournal.applyRun(stored, run) { "fault-$runWrites" }
            return stored
        }
        override suspend fun record(observation: FaultObservation): List<FaultRecord> {
            stored = FaultJournal.apply(stored, observation) { "op-fault-$runWrites" }
            return stored
        }
        override suspend fun clear() { stored = emptyList() }
    }

    private fun probe(scope: DiagnosticScope, action: suspend () -> DiagnosticOutcome) =
        object : DiagnosticProbe {
            override val spec = DiagnosticCheckSpec(
                "source.music.http", DiagnosticComponent.SOURCES,
                DiagnosticImpact.OPTIONAL, scope)
            override suspend fun check(): DiagnosticOutcome = action()
        }

    @Test fun onlyCompletedRunsAreRecorded() = runBlocking {
        val journal = MemoryStore()
        val runner = DiagnosticJournalRunner(DiagnosticCoordinator(listOf(
            probe(DiagnosticScope.LOCAL) { DiagnosticOutcome(
                DiagnosticState.WARNING, DiagnosticEvidence.VERIFIED,
                "Сервер временно ограничил доступ.", "HTTP_ACCESS_DENIED") },
        ), clockMillis = { 500L }), journal)
        val run = runner.execute()
        assertEquals(1, journal.runWrites)
        assertEquals(1, journal.stored.size)
        assertEquals("HTTP_ACCESS_DENIED", journal.stored.single().reasonCode)
        assertEquals(run.findings.single().reasonCode, journal.stored.single().reasonCode)
    }

    @Test fun cancelledRunNeverWritesJournal() = runBlocking {
        val journal = MemoryStore()
        val runner = DiagnosticJournalRunner(DiagnosticCoordinator(listOf(
            probe(DiagnosticScope.LOCAL) { delay(10_000); DiagnosticOutcome(
                DiagnosticState.OK, DiagnosticEvidence.VERIFIED, "Успех") },
        )), journal)
        val started = async { runner.execute() }
        delay(20)
        started.cancelAndJoin()
        assertTrue(started.isCancelled)
        assertEquals(0, journal.runWrites)
        assertTrue(journal.stored.isEmpty())
    }

    @Test fun deniedNetworkRunNeverStartsOrWrites() = runBlocking {
        val journal = MemoryStore()
        var called = false
        val runner = DiagnosticJournalRunner(DiagnosticCoordinator(listOf(
            probe(DiagnosticScope.NETWORK) {
                called = true
                DiagnosticOutcome(DiagnosticState.OK, DiagnosticEvidence.VERIFIED, "Успех")
            },
        )), journal)
        assertTrue(runCatching {
            runner.execute(DiagnosticMode.WITH_NETWORK, networkConsent = false)
        }.isFailure)
        assertFalse(called)
        assertEquals(0, journal.runWrites)
    }
}
