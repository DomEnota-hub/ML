package com.domenota.medialoader.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDiagnosticRulesTest {
    @Test fun runtimeClassPresenceDoesNotMeanYtDlpOrFfmpegIsReady() {
        val ytdlp = LocalDiagnosticRules.runtimeLibrary(true, "yt-dlp")
        val ffmpeg = LocalDiagnosticRules.runtimeLibrary(true, "FFmpeg")
        assertEquals(DiagnosticState.UNAVAILABLE, ytdlp.state)
        assertEquals(DiagnosticEvidence.OBSERVED, ffmpeg.evidence)
        assertFalse(ytdlp.state == DiagnosticState.OK)
        assertEquals(DiagnosticState.ERROR,
            LocalDiagnosticRules.runtimeLibrary(false, "FFmpeg").state)
    }

    @Test fun optionalLamaAbsentIsWarningAndBrokenDigestIsVerifiedError() {
        val notInstalled = LocalDiagnosticRules.model(false, false, null)
        assertEquals(DiagnosticState.WARNING, notInstalled.state)
        assertEquals("LAMA_NOT_INSTALLED", notInstalled.reasonCode)
        val truncated = LocalDiagnosticRules.model(true, false, null)
        assertEquals("LAMA_INVALID_SIZE", truncated.reasonCode)
        val invalid = LocalDiagnosticRules.model(true, true, false)
        assertEquals(DiagnosticState.ERROR, invalid.state)
        assertEquals(DiagnosticEvidence.VERIFIED, invalid.evidence)
        assertEquals(DiagnosticState.OK, LocalDiagnosticRules.model(true, true, true).state)
        assertEquals(DiagnosticState.UNAVAILABLE,
            LocalDiagnosticRules.model(true, true, null).state)
    }

    @Test fun reportedQueueSuccessMeansOnlySnapshotReadNotSuccessfulDownloads() {
        val counts = LocalDiagnosticRules.QueueSnapshot(3, 2, 4, 9, 1)
        val result = LocalDiagnosticRules.queue(counts)
        assertEquals(DiagnosticState.OK, result.state)
        assertTrue(result.explanation.contains("ошибок в истории 9"))
        assertTrue(result.explanation.contains("не проверка скорости"))
        assertEquals(5, counts.active)
    }

    @Test fun foregroundServiceAbsentWithActiveJobsIsNotInventedFailure() {
        assertEquals(DiagnosticState.UNAVAILABLE,
            LocalDiagnosticRules.foreground(2, false).state)
        val seen = LocalDiagnosticRules.foreground(2, true)
        assertEquals(DiagnosticState.UNAVAILABLE, seen.state)
        assertEquals(DiagnosticEvidence.OBSERVED, seen.evidence)
        assertEquals(DiagnosticState.OK, LocalDiagnosticRules.foreground(0, false).state)
    }

    @Test fun revokedSafPermissionIsVerifiedErrorWithoutAttemptedWrite() {
        val denied = LocalDiagnosticRules.selectedFolder(
            hasPersistedRead = true, hasPersistedWrite = false,
            exists = true, isDirectory = true, canRead = true, canWrite = true,
        )
        assertEquals("FOLDER_PERMISSION_MISSING", denied.reasonCode)
        assertEquals(DiagnosticState.ERROR, denied.state)
        val missing = LocalDiagnosticRules.selectedFolder(true, true, false, false, false, false)
        assertEquals("FOLDER_NOT_AVAILABLE", missing.reasonCode)
        val notWritable = LocalDiagnosticRules.selectedFolder(true, true, true, true, true, false)
        assertEquals("FOLDER_ACCESS_DENIED", notWritable.reasonCode)
    }

    @Test fun safMetadataAndMediaStoreNeverClaimActualWritingWasTested() {
        val selected = LocalDiagnosticRules.selectedFolder(true, true, true, true, true, true)
        assertEquals(DiagnosticState.UNAVAILABLE, selected.state)
        assertEquals(DiagnosticEvidence.OBSERVED, selected.evidence)
        assertTrue(selected.explanation.contains("не проверялась"))
        val standard = LocalDiagnosticRules.standardStorage()
        assertEquals(DiagnosticState.UNAVAILABLE, standard.state)
        assertFalse(standard.state == DiagnosticState.OK)
    }

    @Test fun reportedFreeSpaceDoesNotProveFolderWritePermission() {
        val large = LocalDiagnosticRules.freeSpace(5L * 1024 * 1024 * 1024)
        assertEquals(DiagnosticState.OK, large.state)
        assertTrue(large.explanation.contains("не подтверждает"))
        assertEquals(DiagnosticState.WARNING,
            LocalDiagnosticRules.freeSpace(30L * 1024 * 1024).state)
        assertEquals(DiagnosticState.UNAVAILABLE, LocalDiagnosticRules.freeSpace(-1).state)
    }

    @Test fun optionalInstalledModelMustNotMakeWholeAppRed() {
        val mandatory = DiagnosticCheckSpec("queue.snapshot", DiagnosticComponent.DOWNLOAD_QUEUE,
            DiagnosticImpact.REQUIRED)
        val optional = DiagnosticCheckSpec("editor.lama.sha256", DiagnosticComponent.PHOTO_EDITOR,
            DiagnosticImpact.OPTIONAL)
        fun asFinding(spec: DiagnosticCheckSpec, outcome: DiagnosticOutcome) =
            DiagnosticFinding.fromOutcome(spec, outcome, 1_000L, 20L)
        val actual = listOf(
            asFinding(mandatory, LocalDiagnosticRules.queue(
                LocalDiagnosticRules.QueueSnapshot(0, 0, 0, 0, 0))),
            asFinding(optional, LocalDiagnosticRules.model(true, true, false)),
        )
        assertEquals(DiagnosticState.WARNING,
            DiagnosticAssessment.application(listOf(mandatory, optional), actual, 1_100L).state)
        assertEquals(DiagnosticState.ERROR,
            DiagnosticAssessment.component(DiagnosticComponent.PHOTO_EDITOR,
                listOf(mandatory, optional), actual, 1_100L).state)
    }

    @Test fun offlineProbeResponsesDoNotContainSecretsOrRemoteUrls() {
        val tests = listOf(
            LocalDiagnosticRules.standardStorage(),
            LocalDiagnosticRules.runtimeLibrary(true, "yt-dlp"),
            LocalDiagnosticRules.model(true, true, false),
            LocalDiagnosticRules.foreground(2, false),
        )
        assertTrue(tests.none { it.explanation.contains("https://") })
        assertTrue(tests.all { it.reasonCode != null })
    }
}
