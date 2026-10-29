package com.domenota.medialoader.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticAssessmentTest {
    private val queue = DiagnosticCheckSpec("queue.health", DiagnosticComponent.DOWNLOAD_QUEUE,
        DiagnosticImpact.REQUIRED, maxAgeMillis = 1_000L)
    private val storage = DiagnosticCheckSpec("storage.access", DiagnosticComponent.STORAGE,
        DiagnosticImpact.REQUIRED, maxAgeMillis = 1_000L)
    private val lama = DiagnosticCheckSpec("editor.lama", DiagnosticComponent.PHOTO_EDITOR,
        DiagnosticImpact.OPTIONAL, maxAgeMillis = 1_000L)
    private val specs = listOf(queue, storage, lama)

    private fun finding(
        spec: DiagnosticCheckSpec,
        state: DiagnosticState = DiagnosticState.OK,
        checkedAt: Long = 500L,
    ) = DiagnosticFinding.fromOutcome(spec,
        DiagnosticOutcome(state, DiagnosticEvidence.VERIFIED,
            "Проверка выполнена."), checkedAt, 5L)

    @Test fun absentResultsNeverBecomeGreen() {
        assertEquals(DiagnosticState.UNCHECKED,
            DiagnosticAssessment.application(specs, emptyList(), 700L).state)
        val partial = DiagnosticAssessment.application(specs, listOf(finding(queue)), 700L)
        assertEquals(DiagnosticState.UNCHECKED, partial.state)
        assertEquals(2, partial.missingResults)
    }

    @Test fun installedOrHistoricalEvidenceCannotClaimSuccess() {
        assertTrue(runCatching {
            DiagnosticOutcome(DiagnosticState.OK, DiagnosticEvidence.OBSERVED,
                "Файл найден.")
        }.isFailure)
        assertTrue(runCatching {
            DiagnosticOutcome(DiagnosticState.ERROR, DiagnosticEvidence.HISTORICAL,
                "Старая ошибка.")
        }.isFailure)
        assertTrue(runCatching {
            DiagnosticOutcome(DiagnosticState.OK, DiagnosticEvidence.VERIFIED,
                "Проверено.", reasonCode = "token=secret")
        }.isFailure)
        assertTrue(runCatching {
            DiagnosticOutcome(DiagnosticState.OK, DiagnosticEvidence.VERIFIED,
                "Проверено.", relatedEventIds = listOf("https://secret.invalid/a"))
        }.isFailure)
    }

    @Test fun optionalModelAbsentDoesNotTurnWholeAppRed() {
        val all = listOf(finding(queue), finding(storage),
            finding(lama, DiagnosticState.ERROR))
        assertEquals(DiagnosticState.WARNING,
            DiagnosticAssessment.application(specs, all, 700L).state)
        assertEquals(DiagnosticState.ERROR,
            DiagnosticAssessment.component(DiagnosticComponent.PHOTO_EDITOR,
                specs, all, 700L).state)
        // The optional model was never checked: no warning about the app as a whole.
        assertEquals(DiagnosticState.OK,
            DiagnosticAssessment.application(specs, all.take(2), 700L).state)
    }

    @Test fun requiredVerifiedFailureIsRedButCheckExceptionIsNot() {
        assertEquals(DiagnosticState.ERROR,
            DiagnosticAssessment.application(specs,
                listOf(finding(queue, DiagnosticState.ERROR), finding(storage)), 700L).state)
        val unavailable = DiagnosticFinding.fromOutcome(queue,
            DiagnosticOutcome.unavailable("Не удалось запустить тест.", "CHECK_EXCEPTION"),
            500L, 3L)
        assertEquals(DiagnosticState.UNAVAILABLE,
            DiagnosticAssessment.application(specs,
                listOf(unavailable, finding(storage)), 700L).state)
    }

    @Test fun staleEvidenceNeverTurnsGreen() {
        val all = listOf(finding(queue), finding(storage), finding(lama))
        assertEquals(DiagnosticState.OK,
            DiagnosticAssessment.application(specs, all, 1_500L).state)
        val stale = DiagnosticAssessment.application(specs, all, 1_501L)
        assertEquals(DiagnosticState.UNCHECKED, stale.state)
        assertEquals(3, stale.expiredResults)
        assertEquals(0, stale.freshResults)
        assertTrue(all[0].isStale(1_501L))
        assertFalse(all[0].isStale(1_500L))
        assertTrue(all[0].isStale(499L)) // Clock rolled back.
    }

    @Test fun unavailableAndNotCheckedAreDifferentFromFailed() {
        val results = listOf(
            DiagnosticFinding.fromOutcome(queue,
                DiagnosticOutcome.unavailable("Проверка не поддерживается.", "NO_SAFE_PROBE"),
                100L, 0L),
            DiagnosticFinding.notChecked(storage, "Проверка не запускалась."),
        )
        assertEquals(DiagnosticState.UNCHECKED,
            DiagnosticAssessment.application(specs, results, 150L).state)
        assertEquals(DiagnosticState.UNAVAILABLE,
            DiagnosticAssessment.component(DiagnosticComponent.DOWNLOAD_QUEUE,
                specs, results, 150L).state)
    }

    @Test fun completedWriteSupersedesReadOnlyFolderUncertainty() {
        val folder = DiagnosticCheckSpec("storage.selected_folder", DiagnosticComponent.STORAGE,
            DiagnosticImpact.REQUIRED)
        val write = DiagnosticCheckSpec("storage.write", DiagnosticComponent.STORAGE,
            DiagnosticImpact.OPTIONAL, DiagnosticScope.FULL)
        val limited = DiagnosticFinding.fromOutcome(folder,
            DiagnosticOutcome.unavailable("Запись не проверена", "FOLDER_WRITE_UNVERIFIED"),
            1_000, 2)
        val verified = DiagnosticFinding.fromOutcome(write,
            DiagnosticOutcome(DiagnosticState.OK, DiagnosticEvidence.VERIFIED,
                "Файл записан и прочитан", "STORAGE_WRITE_OK"), 1_010, 10)
        val findings = listOf(limited, verified)
        assertEquals(DiagnosticState.OK,
            DiagnosticAssessment.application(listOf(folder, write), findings, 1_020).state)
        assertEquals(DiagnosticState.OK,
            DiagnosticAssessment.component(DiagnosticComponent.STORAGE,
                listOf(folder, write), findings, 1_020).state)
    }

    @Test fun fullSourceFailureCannotProduceHealthyOverallStatus() {
        val metadata = DiagnosticCheckSpec("source.vk.auth", DiagnosticComponent.SOURCES,
            DiagnosticImpact.OPTIONAL)
        val workflow = DiagnosticCheckSpec("source.vk.workflow", DiagnosticComponent.SOURCES,
            DiagnosticImpact.OPTIONAL, DiagnosticScope.FULL)
        val saved = DiagnosticFinding.fromOutcome(metadata,
            DiagnosticOutcome(DiagnosticState.UNAVAILABLE, DiagnosticEvidence.OBSERVED,
                "Cookies сохранены", "AUTH_SERVER_NOT_VERIFIED"), 500, 1)
        val blocked = DiagnosticFinding.fromOutcome(workflow,
            DiagnosticOutcome.unavailable("Данные не получены", "WORKFLOW_ACCESS_LIMITED"), 500, 4)
        val allSpecs = specs + metadata + workflow
        val allFindings = listOf(finding(queue), finding(storage), finding(lama), saved, blocked)
        assertEquals(DiagnosticState.UNAVAILABLE,
            DiagnosticAssessment.application(allSpecs, allFindings, 700).state)
        assertEquals(DiagnosticState.UNAVAILABLE,
            DiagnosticAssessment.component(DiagnosticComponent.SOURCES, allSpecs, allFindings, 700).state)
    }

    @Test fun successfulSourceWorkflowIsNotOverriddenByInconclusiveAccountProbe() {
        val workflow = DiagnosticCheckSpec("source.rutube.workflow", DiagnosticComponent.SOURCES,
            DiagnosticImpact.OPTIONAL, DiagnosticScope.FULL)
        val session = DiagnosticCheckSpec("source.rutube.session", DiagnosticComponent.SOURCES,
            DiagnosticImpact.OPTIONAL, DiagnosticScope.FULL)
        val results = listOf(finding(workflow),
            DiagnosticFinding.fromOutcome(session,
                DiagnosticOutcome.unavailable("Профиль не распознан", "SESSION_PROFILE_UNRECOGNIZED"),
                500, 2))
        assertEquals(DiagnosticState.OK,
            DiagnosticAssessment.component(DiagnosticComponent.SOURCES,
                listOf(workflow, session), results, 700).state)
    }
}
