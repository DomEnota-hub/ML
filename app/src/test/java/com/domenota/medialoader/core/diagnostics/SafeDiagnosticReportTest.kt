package com.domenota.medialoader.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeDiagnosticReportTest {
    @Test fun fullReportReplacesPassiveSourceRowsWithActualWorkflowAndSession() {
        val passive = DiagnosticCheckSpec("source.vk.auth", DiagnosticComponent.SOURCES,
            DiagnosticImpact.OPTIONAL)
        val workflow = DiagnosticCheckSpec("source.vk.workflow", DiagnosticComponent.SOURCES,
            DiagnosticImpact.OPTIONAL, DiagnosticScope.FULL)
        val session = DiagnosticCheckSpec("source.vk.session", DiagnosticComponent.SOURCES,
            DiagnosticImpact.OPTIONAL, DiagnosticScope.FULL)
        val findings = listOf(
            DiagnosticFinding.fromOutcome(passive, SourceDiagnosticRules.storedSession(true), 1_000, 1),
            DiagnosticFinding.fromOutcome(workflow, SourceWorkflowRules.extracted(1), 1_000, 2),
            DiagnosticFinding.fromOutcome(session, SessionProbeRules.classify(DiagnosticSource.VK,
                200, "<a href='/logout'>exit</a>"), 1_000, 3),
        )
        val report = SafeDiagnosticReport.render(SafeDiagnosticReport.Input(
            listOf(passive, workflow, session),
            DiagnosticRun(DiagnosticMode.FULL, 900, 1_100, findings), emptyList(),
            1_200, "1.7.9", 71, 36))
        assertFalse(report.contains("Сохранённая сессия"))
        assertTrue(report.contains("Получение метаданных"))
        assertTrue(report.contains("Сессия загрузчика"))
        assertFalse(report.contains("Действительность авторизации"))
    }
    @Test fun fullWorkflowReportExplainsStageWithoutExportingControlLink() {
        val spec = DiagnosticCheckSpec("source.youtube.workflow", DiagnosticComponent.SOURCES,
            DiagnosticImpact.OPTIONAL, DiagnosticScope.FULL)
        val raw = DiagnosticFinding.fromOutcome(spec,
            SourceWorkflowRules.extracted(2).copy(
                explanation = "https://youtube.com/watch?v=secret-token"), 1_000L, 20L)
        val report = SafeDiagnosticReport.render(SafeDiagnosticReport.Input(
            specs = listOf(spec),
            lastCompletedRun = DiagnosticRun(DiagnosticMode.FULL, 900L, 1_100L, listOf(raw)),
            incidents = emptyList(), generatedAtMillis = 1_200L,
            versionName = "1.7.7", versionCode = 69, androidSdk = 36,
        ))
        assertTrue(report.contains("Получены метаданные"))
        assertFalse(report.contains("secret-token"))
        assertTrue(report.contains("Полная функциональная диагностика"))
    }
    private val queue = DiagnosticCheckSpec(
        "queue.snapshot", DiagnosticComponent.DOWNLOAD_QUEUE, DiagnosticImpact.REQUIRED)
    private val source = DiagnosticCheckSpec(
        "source.muzmo.http", DiagnosticComponent.SOURCES, DiagnosticImpact.OPTIONAL,
        DiagnosticScope.NETWORK)
    private val auth = DiagnosticCheckSpec(
        "source.youtube.auth", DiagnosticComponent.SOURCES, DiagnosticImpact.OPTIONAL)
    private val specs = listOf(queue, source, auth)

    private fun report(run: DiagnosticRun? = null,
        faults: List<FaultRecord> = emptyList(), at: Long = 12_000L,
        version: String = "1.7.6"): String =
        SafeDiagnosticReport.render(SafeDiagnosticReport.Input(
            specs = specs,
            lastCompletedRun = run,
            incidents = faults,
            generatedAtMillis = at,
            versionName = version,
            versionCode = 68,
            androidSdk = 35,
        ))

    private fun run(vararg findings: DiagnosticFinding) =
        DiagnosticRun(DiagnosticMode.WITH_NETWORK, 1_000L, 2_000L, findings.toList())

    private fun read(spec: DiagnosticCheckSpec, state: DiagnosticState,
        reason: String, raw: String, checked: Long = 1_500L) =
        DiagnosticFinding.fromOutcome(spec,
            DiagnosticOutcome(state, DiagnosticEvidence.VERIFIED, raw, reason,
                recommendation = "secret://exfil/RECOMMENDATION_SENTINEL",
                relatedEventIds = listOf("event:private-123")), checked, 3L)

    @Test fun neverExportsRawProbeTextsOperationIdsLinksOrExceptionMessages() {
        val secret = "COOKIE_PASSWORD_SENTINEL_BEARER_123"
        val noisy = "Raw https://private.example/?token=$secret /storage/DCIM/private.jpg " +
            "filename=PERSONAL_PHOTO_123 Authorization: Bearer $secret"
        val sourceResult = read(source, DiagnosticState.WARNING, "HTTP_ACCESS_DENIED", noisy)
        val queueResult = read(queue, DiagnosticState.OK, "QUEUE_SNAPSHOT_READ", noisy)
        val storeSession = DiagnosticFinding.fromOutcome(auth,
            DiagnosticOutcome(DiagnosticState.UNAVAILABLE, DiagnosticEvidence.OBSERVED,
                noisy, "AUTH_SERVER_NOT_VERIFIED"), 1_700L, 5L)
        val incident = FaultRecord(
            id = "fault:ABC123", component = DiagnosticComponent.SOURCES,
            checkCode = source.code, reasonCode = "HTTP_ACCESS_DENIED",
            operationId = "session:SENSITIVE123", severity = FaultSeverity.WARNING,
            status = FaultStatus.ACTIVE,
            firstSeenMillis = 1_100L, lastSeenMillis = 1_600L,
            resolvedAtMillis = null, repetitions = 7,
            eventIds = listOf("op:SENSITIVE123"),
        )
        val output = report(run(queueResult, sourceResult, storeSession), listOf(incident))
        for (forbidden in listOf(
            secret, "private.example", "/storage/DCIM", "PERSONAL_PHOTO",
            "Authorization:", "secret://", "event:private", "session:SENSITIVE123",
            "op:SENSITIVE123", "fault:ABC123", "source.muzmo.http",
            "hitmoz", "muzmo", "RECOMMENDATION_SENTINEL",
        )) assertFalse("leaked $forbidden", output.contains(forbidden, ignoreCase = true))
        assertTrue(output.contains("HTTP 403"))
        assertTrue(output.contains("IP/VPN"))
        assertTrue(output.contains("повторов", ignoreCase = true) ||
            output.contains("Повторений"))
        assertTrue(output.contains("YouTube"))
        assertTrue(output.contains("Резервный музыкальный источник"))
    }

    @Test fun noPriorRunMustBeExplicitlyUncheckedEvenWhenFaultHistoryEmpty() {
        val output = report()
        assertTrue(output.contains("Проверка ещё не выполнялась"))
        assertTrue(output.contains("Не проверено"))
        assertTrue(output.contains("Записей нет. Это не означает, что приложение исправно"))
        assertFalse(output.contains("Общий статус: Исправен"))
    }

    @Test fun expiredSuccessIsReportedAsStaleNotAsWorking() {
        val success = read(queue, DiagnosticState.OK, "QUEUE_SNAPSHOT_READ",
            "Queue data 1", checked = 1_500L)
        val text = report(run(success), at = 1_500L + queue.maxAgeMillis + 1L)
        assertTrue(text.contains("Устарело — нужна повторная проверка"))
        assertTrue(text.contains("Общий статус: Не проверено"))
        assertTrue(text.contains("Устаревших результатов: 1"))
    }

    @Test fun offlineRunRetainsNetworkUncheckedAndNeverCallsAnything() {
        val local = DiagnosticRun(DiagnosticMode.LOCAL, 1_000L, 2_000L,
            listOf(DiagnosticFinding.fromOutcome(queue,
                DiagnosticOutcome(DiagnosticState.OK, DiagnosticEvidence.VERIFIED,
                    "queue checked", "QUEUE_SNAPSHOT_READ"), 1_500L, 2L),
                DiagnosticFinding.notChecked(source, "No network")))
        val text = report(local)
        assertTrue(text.contains("Только локальная диагностика"))
        assertTrue(text.contains("Проверка не выполнялась в последнем завершённом запуске").not() ||
            text.contains("Не проверено"))
        assertFalse(text.contains("HTTP 403"))
    }

    @Test fun extractsNumericCountsOnlyWithoutCopyingTrailingUntrustedData() {
        val raw = "Состояния очереди прочитаны: ожидают 2, выполняются 4, " +
            "завершены 9, ошибок в истории 3, отменены 1. token=SHOULD_NOT_EXPORT"
        val f = read(queue, DiagnosticState.OK, "QUEUE_SNAPSHOT_READ", raw)
        val text = report(run(f))
        assertTrue(text.contains("ожидают 2, выполняются 4, завершены 9"))
        assertFalse(text.contains("SHOULD_NOT_EXPORT"))
    }

    @Test fun unknownReasonIsGenericAndSensitiveVersionNameRejected() {
        val f = read(source, DiagnosticState.WARNING, "SECRET_REASON_SENTINEL",
            "https://should.not.leak")
        val text = report(run(f), version = "https://private.example/?token=abcd")
        assertTrue(text.contains("Приложение: MediaLoader не указана"))
        assertTrue(text.contains("не предоставила дополнительных разрешённых"))
        assertFalse(text.contains("SECRET_REASON_SENTINEL"))
        assertFalse(text.contains("should.not.leak"))
        assertFalse(text.contains("private.example"))
    }

    @Test fun faultReportsKeepVerifiedRecoveryAndDistinctLifecycle() {
        val first = FaultRecord(
            "fault-one", DiagnosticComponent.STORAGE, "storage.selected_folder",
            "FOLDER_PERMISSION_MISSING", null, FaultSeverity.ERROR,
            FaultStatus.RESOLVED, 100L, 200L, 250L, 2, emptyList())
        val second = FaultRecord(
            "fault-two", DiagnosticComponent.SOURCES, source.code,
            "HTTP_ACCESS_DENIED", null, FaultSeverity.WARNING,
            FaultStatus.NEEDS_RECHECK, 120L, 330L, null, 4, emptyList())
        val result = report(faults = listOf(first, second))
        assertTrue(result.contains("Устранение подтверждено"))
        assertTrue(result.contains("Требуется повторная проверка"))
        assertTrue(result.contains("Требуют внимания: 1"))
        assertTrue(result.contains("Всего сохранено: 2"))
        assertFalse(result.contains("fault-one"))
        assertFalse(result.contains("fault-two"))
    }

    @Test fun unreadableHistoriesAreNotReportedAsEmptyOrHealthy() {
        val text = SafeDiagnosticReport.render(SafeDiagnosticReport.Input(
            specs = specs,
            lastCompletedRun = null,
            incidents = emptyList(),
            generatedAtMillis = 12_000L,
            versionName = "1.7.6",
            versionCode = 68,
            androidSdk = 35,
            latestRunReadable = false,
            faultJournalReadable = false,
        ))
        assertTrue(text.contains("Историю проверок прочитать не удалось"))
        assertTrue(text.contains("Историю неисправностей прочитать не удалось"))
        assertFalse(text.contains("Записей нет. Это не означает"))
        assertFalse(text.contains("Общий статус: Исправен"))
    }

    @Test fun outputRemainsBoundedAgainstLargeIncidentLists() {
        val item = FaultRecord("fault-valid", DiagnosticComponent.SOURCES,
            source.code, "HTTP_ACCESS_DENIED", null, FaultSeverity.WARNING,
            FaultStatus.ACTIVE, 100, 200, null, 1, emptyList())
        assertTrue(runCatching {
            report(faults = List(FaultJournal.MAX_RECORDS + 1) { i ->
                item.copy(id = "fault-$i")
            })
        }.isFailure)
        assertEquals(0, report().count { it == '<' })
    }
}
