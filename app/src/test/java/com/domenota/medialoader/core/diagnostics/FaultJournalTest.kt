package com.domenota.medialoader.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultJournalTest {
    private fun signal(
        check: String = "source.muzmo.http",
        reason: String = "HTTP_ACCESS_DENIED",
        operation: String? = null,
        at: Long = 100L,
        severity: FaultSeverity = FaultSeverity.WARNING,
        links: List<String> = emptyList(),
        component: DiagnosticComponent = DiagnosticComponent.SOURCES,
    ) = FaultSignal(component, check, reason, severity, at, operation, links)

    private var index = 0
    private fun newId(): String = "fault-${++index}"

    @Test fun repeatedFaultsCoalesceOnlyWithinTheSameExactIdentity() {
        val first = FaultJournal.apply(emptyList(),
            FaultObservation.Failure(signal(links = listOf("event:01"))), ::newId)
        val repeat = FaultJournal.apply(first,
            FaultObservation.Failure(signal(at = 200, links = listOf("event:02"))), ::newId)
        assertEquals(1, repeat.size)
        assertEquals(2, repeat.single().repetitions)
        assertEquals(100L, repeat.single().firstSeenMillis)
        assertEquals(200L, repeat.single().lastSeenMillis)
        assertEquals(listOf("event:01", "event:02"), repeat.single().eventIds)
        val differentReason = FaultJournal.apply(repeat,
            FaultObservation.Failure(signal(reason = "HTTP_RATE_LIMIT", at = 201)), ::newId)
        val differentOperation = FaultJournal.apply(differentReason,
            FaultObservation.Failure(signal(operation = "search-19", at = 202)), ::newId)
        val differentComponent = FaultJournal.apply(differentOperation,
            FaultObservation.Failure(signal(component = DiagnosticComponent.MEDIA_RUNTIME,
                at = 203)), ::newId)
        assertEquals(4, differentComponent.size)
        assertEquals(1, differentComponent.count { it.repetitions == 2 })
    }

    @Test fun recoveryDoesNotResolveUnrelatedSourceOrOperation() {
        var records = FaultJournal.apply(emptyList(),
            FaultObservation.Failure(signal(operation = "search-1")), ::newId)
        records = FaultJournal.apply(records,
            FaultObservation.Failure(signal(check = "source.hitmoz.http",
                reason = "HTTP_RATE_LIMIT", operation = "search-2")), ::newId)
        records = FaultJournal.apply(records,
            FaultObservation.Failure(signal(operation = "search-3")), ::newId)
        records = FaultJournal.apply(records, FaultObservation.Recovered(
            DiagnosticComponent.SOURCES, "source.muzmo.http", 300, "search-1"), ::newId)
        assertEquals(FaultStatus.RESOLVED,
            records.single { it.operationId == "search-1" }.status)
        assertTrue(records.filter { it.operationId != "search-1" }
            .all { it.status == FaultStatus.ACTIVE })
        assertEquals(300L, records.single { it.operationId == "search-1" }.resolvedAtMillis)
    }

    @Test fun verifiedRecoveryOfOneCheckCannotResolveOtherChecksInSameComponent() {
        var records = FaultJournal.apply(emptyList(),
            FaultObservation.Failure(signal()), ::newId)
        records = FaultJournal.apply(records,
            FaultObservation.Failure(signal(check = "source.muzmo.history",
                reason = "SOURCE_HISTORY_PROBLEM", at = 110)), ::newId)
        records = FaultJournal.apply(records,
            FaultObservation.Recovered(DiagnosticComponent.SOURCES,
                "source.muzmo.http", atMillis = 150), ::newId)
        assertEquals(FaultStatus.RESOLVED,
            records.single { it.checkCode == "source.muzmo.http" }.status)
        assertEquals(FaultStatus.ACTIVE,
            records.single { it.checkCode == "source.muzmo.history" }.status)
        records = FaultJournal.apply(records,
            FaultObservation.Failure(signal(at = 170)), ::newId)
        assertEquals(3, records.size)
        assertEquals(2, records.count { it.checkCode == "source.muzmo.http" })
        assertTrue(records.any { it.checkCode == "source.muzmo.http" &&
            it.status == FaultStatus.RESOLVED })
    }

    @Test fun unsuccessfulRecheckDoesNotPretendOriginalIssueIsFixed() {
        var records = FaultJournal.apply(emptyList(),
            FaultObservation.Failure(signal()), ::newId)
        records = FaultJournal.apply(records,
            FaultObservation.RecheckRequired(DiagnosticComponent.SOURCES,
                "source.muzmo.http"), ::newId)
        assertEquals(FaultStatus.NEEDS_RECHECK, records.single().status)
        assertEquals(null, records.single().resolvedAtMillis)
        records = FaultJournal.apply(records,
            FaultObservation.Failure(signal(at = 200)), ::newId)
        assertEquals(FaultStatus.ACTIVE, records.single().status)
        assertEquals(2, records.single().repetitions)
    }

    @Test fun oldOutOfOrderSignalsCannotIncreaseRepetitionOrReopenResolvedIncident() {
        var records = FaultJournal.apply(emptyList(),
            FaultObservation.Failure(signal(at = 300)), ::newId)
        records = FaultJournal.apply(records,
            FaultObservation.Failure(signal(at = 200)), ::newId)
        assertEquals(1, records.single().repetitions)
        records = FaultJournal.apply(records,
            FaultObservation.Recovered(DiagnosticComponent.SOURCES,
                "source.muzmo.http", 299), ::newId)
        assertEquals(FaultStatus.ACTIVE, records.single().status)
        records = FaultJournal.apply(records,
            FaultObservation.Recovered(DiagnosticComponent.SOURCES,
                "source.muzmo.http", 400), ::newId)
        assertEquals(FaultStatus.RESOLVED, records.single().status)
        records = FaultJournal.apply(records,
            FaultObservation.Failure(signal(at = 450)), ::newId)
        assertEquals(2, records.size)
        assertEquals(FaultStatus.ACTIVE, records.last().status)
    }

    @Test fun incidentHistoryIsBoundedAndReferencesAreBounded() {
        var records = emptyList<FaultRecord>()
        for (i in 0..(FaultJournal.MAX_RECORDS + 10)) {
            records = FaultJournal.apply(records,
                FaultObservation.Failure(signal(check = "source.${i}.http",
                    at = 100L + i)), ::newId)
        }
        assertEquals(FaultJournal.MAX_RECORDS, records.size)
        assertFalse(records.any { it.checkCode == "source.0.http" })
        val linked = FaultJournal.apply(emptyList(),
            FaultObservation.Failure(signal(links = (1..30).map { "event:$it" })), ::newId)
        assertEquals(FaultJournal.MAX_LINKS, linked.single().eventIds.size)
    }

    @Test fun rejectsSecretBearingCodesAndIdentifiers() {
        assertTrue(runCatching { signal(reason = "https://site.invalid/?token=secret") }.isFailure)
        assertTrue(runCatching { signal(operation = "sessionid=secret") }.isFailure)
        assertTrue(runCatching { signal(links = listOf("http://site.invalid/key")) }.isFailure)
        assertTrue(runCatching { signal(check = "https://site.invalid") }.isFailure)
    }

    @Test fun anonymous403DoesNotOpenFaultAndRealFailureDoes() {
        val check = DiagnosticCheckSpec("source.muzmo.http", DiagnosticComponent.SOURCES,
            DiagnosticImpact.OPTIONAL, DiagnosticScope.NETWORK)
        val failure = DiagnosticFinding.fromOutcome(check,
            SourceDiagnosticRules.http(403), 200, 30)
        var records = FaultJournal.applyRun(emptyList(),
            DiagnosticRun(DiagnosticMode.WITH_NETWORK, 100, 300, listOf(failure)), ::newId)
        assertTrue(records.isEmpty())
        val realCheck = check.copy(code = "source.muzmo.workflow")
        val realFailure = DiagnosticFinding.fromOutcome(realCheck,
            DiagnosticOutcome(DiagnosticState.ERROR, DiagnosticEvidence.VERIFIED,
                "Рабочий поиск завершился ошибкой.", "WORKFLOW_FAILED"), 320, 10)
        records = FaultJournal.applyRun(records,
            DiagnosticRun(DiagnosticMode.WITH_NETWORK, 310, 330, listOf(realFailure)), ::newId)
        assertEquals(1, records.size)
        assertEquals(FaultSeverity.ERROR, records.single().severity)
        val skipped = DiagnosticFinding.notChecked(check, "Не запускалась")
        records = FaultJournal.applyRun(records,
            DiagnosticRun(DiagnosticMode.LOCAL, 400, 500, listOf(skipped)), ::newId)
        assertEquals(FaultStatus.ACTIVE, records.single().status)
        val unreadable = DiagnosticFinding.fromOutcome(check,
            DiagnosticOutcome.unavailable("Проверка недоступна", "NETWORK_DNS_FAILED"), 600, 10)
        records = FaultJournal.applyRun(records,
            DiagnosticRun(DiagnosticMode.WITH_NETWORK, 550, 650, listOf(unreadable)), ::newId)
        assertEquals(FaultStatus.ACTIVE, records.single().status)
        val success = DiagnosticFinding.fromOutcome(realCheck,
            DiagnosticOutcome(DiagnosticState.OK, DiagnosticEvidence.VERIFIED,
                "Поиск выполнен."), 720, 10)
        records = FaultJournal.applyRun(records,
            DiagnosticRun(DiagnosticMode.WITH_NETWORK, 700, 730, listOf(success)), ::newId)
        assertEquals(FaultStatus.RESOLVED, records.single().status)
        assertEquals(720L, records.single().resolvedAtMillis)
    }

    @Test fun explanationDoesNotEchoHostUserInputOrCredentials() {
        val record = FaultJournal.apply(emptyList(),
            FaultObservation.Failure(signal()), ::newId).single()
        val copy = record.copy(reasonCode = "UNKNOWN_CAUSE")
        val desc = FaultExplanations.forRecord(copy)
        assertTrue(desc.title.contains("отклонение", ignoreCase = true))
        assertFalse(desc.explanation.contains("muzmo", ignoreCase = true))
        val http = FaultExplanations.forRecord(record)
        assertTrue(http.explanation.contains("HTTP 403"))
        assertFalse(http.explanation.contains("rmr.muzmo.cc"))
    }
}
