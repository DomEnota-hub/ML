package com.domenota.medialoader.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDiagnosticRulesTest {
    @Test fun allSixKnownSourcesHaveUniqueStableIdsAndNeutralMusicLabels() {
        val known = DiagnosticSource.entries
        assertEquals(6, known.size)
        assertEquals(6, known.map { it.id }.distinct().size)
        assertEquals(4, known.count { it.requiresAccountCheck })
        assertEquals("Основной музыкальный источник", DiagnosticSource.PRIMARY_MUSIC.label)
        assertEquals("Резервный музыкальный источник", DiagnosticSource.RESERVE_MUSIC.label)
        assertFalse(DiagnosticSource.PRIMARY_MUSIC.label.contains("hitmoz", ignoreCase = true))
        assertFalse(DiagnosticSource.RESERVE_MUSIC.label.contains("muzmo", ignoreCase = true))
    }

    @Test fun storedCookiesAreNotProofOfAuthenticatedAccess() {
        val present = SourceDiagnosticRules.storedSession(true)
        assertEquals(DiagnosticState.UNAVAILABLE, present.state)
        assertEquals(DiagnosticEvidence.OBSERVED, present.evidence)
        assertTrue(present.explanation.contains("не проверялась"))
        val absent = SourceDiagnosticRules.storedSession(false)
        assertEquals(DiagnosticState.UNAVAILABLE, absent.state)
        assertEquals(DiagnosticEvidence.VERIFIED, absent.evidence)
        assertFalse(absent.state == DiagnosticState.ERROR)
    }

    @Test fun documentReachabilityNeverClaimsMusicSearchOrDownloadIsWorking() {
        val response = SourceDiagnosticRules.http(200)
        assertEquals(DiagnosticState.OK, response.state)
        assertEquals(DiagnosticEvidence.VERIFIED, response.evidence)
        assertTrue(response.explanation.contains("не проверены"))
        assertEquals("HTTP_DOCUMENT_REACHABLE", response.reasonCode)
    }

    @Test fun vpnRelated403OnlyLimitsAnonymousProbe() {
        val response = SourceDiagnosticRules.http(403)
        assertEquals(DiagnosticState.UNAVAILABLE, response.state)
        assertEquals(DiagnosticEvidence.VERIFIED, response.evidence)
        assertEquals("HTTP_ACCESS_DENIED", response.reasonCode)
        assertTrue(response.explanation.contains("IP или VPN"))
        assertTrue(response.recommendation!!.contains("не доказательство поломки"))
    }

    @Test fun rateLimitingAndAuthenticationRequiredAreNotSuccessfulChecks() {
        assertEquals("HTTP_RATE_LIMIT", SourceDiagnosticRules.http(429).reasonCode)
        assertEquals(DiagnosticState.UNAVAILABLE, SourceDiagnosticRules.http(429).state)
        assertEquals(DiagnosticState.UNAVAILABLE, SourceDiagnosticRules.http(401).state)
        assertEquals(DiagnosticState.UNAVAILABLE, SourceDiagnosticRules.http(503).state)
        assertEquals(DiagnosticState.UNAVAILABLE, SourceDiagnosticRules.http(404).state)
        assertEquals(DiagnosticState.UNAVAILABLE, SourceDiagnosticRules.http(302).state)
    }

    @Test fun aTransportFailureIsUnverifiedNotConfirmedSourceFailure() {
        for (issue in SourceDiagnosticRules.NetworkIssue.entries) {
            val outcome = SourceDiagnosticRules.transportFailed(issue)
            assertEquals(DiagnosticState.UNAVAILABLE, outcome.state)
            assertEquals(DiagnosticEvidence.NONE, outcome.evidence)
            assertFalse(outcome.state == DiagnosticState.ERROR)
        }
        assertEquals("NETWORK_DNS_FAILED",
            SourceDiagnosticRules.transportFailed(
                SourceDiagnosticRules.NetworkIssue.NAME_RESOLUTION).reasonCode)
    }

    @Test fun historicalSuccessAndFailureCannotTurnSourceGreenOrRed() {
        val previous = SourceDiagnosticRules.history(21, 5, 2)
        assertEquals(DiagnosticState.UNAVAILABLE, previous.state)
        assertEquals(DiagnosticEvidence.HISTORICAL, previous.evidence)
        assertTrue(previous.explanation.contains("завершено 21"))
        assertTrue(previous.explanation.contains("не подтверждает"))
        val empty = SourceDiagnosticRules.history(0, 0, 0)
        assertEquals(DiagnosticState.UNAVAILABLE, empty.state)
        assertEquals(DiagnosticEvidence.NONE, empty.evidence)
        assertTrue(runCatching { SourceDiagnosticRules.history(-1, 0, 0) }.isFailure)
    }

    @Test fun safeSourceProbeStatusesDoNotTurnTheWholeAppRed() {
        val mandatory = DiagnosticCheckSpec(
            "queue.snapshot", DiagnosticComponent.DOWNLOAD_QUEUE, DiagnosticImpact.REQUIRED)
        val reachability = DiagnosticCheckSpec(
            "source.muzmo.http", DiagnosticComponent.SOURCES, DiagnosticImpact.OPTIONAL,
            scope = DiagnosticScope.NETWORK)
        val evidence = listOf(
            DiagnosticFinding.fromOutcome(mandatory,
                DiagnosticOutcome(DiagnosticState.OK, DiagnosticEvidence.VERIFIED,
                    "Очередь прочитана."), 100L, 3L),
            DiagnosticFinding.fromOutcome(reachability,
                SourceDiagnosticRules.http(403), 100L, 5L),
        )
        assertEquals(DiagnosticState.OK,
            DiagnosticAssessment.application(listOf(mandatory, reachability), evidence, 200L).state)
        assertEquals(DiagnosticState.UNAVAILABLE,
            DiagnosticAssessment.component(DiagnosticComponent.SOURCES,
                listOf(mandatory, reachability), evidence, 200L).state)
    }
}
