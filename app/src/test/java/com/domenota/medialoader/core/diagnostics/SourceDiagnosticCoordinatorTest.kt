package com.domenota.medialoader.core.diagnostics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDiagnosticCoordinatorTest {
    @Test fun allSixNetworkProbesRemainDormantInLocalMode() = runBlocking {
        var requestCount = 0
        val sources = DiagnosticSource.entries.map { source ->
            object : DiagnosticProbe {
                override val spec = DiagnosticCheckSpec(
                    "source.${source.id}.http", DiagnosticComponent.SOURCES,
                    DiagnosticImpact.OPTIONAL, DiagnosticScope.NETWORK)
                override suspend fun check(): DiagnosticOutcome {
                    requestCount++
                    return SourceDiagnosticRules.http(200)
                }
            }
        }
        val coordinator = DiagnosticCoordinator(sources, clockMillis = { 100L })
        val local = coordinator.run(DiagnosticMode.LOCAL)
        assertEquals(0, requestCount)
        assertEquals(6, local.findings.size)
        assertTrue(local.findings.all {
            it.state == DiagnosticState.UNCHECKED && it.checkedAtMillis == null
        })
        assertTrue(runCatching {
            coordinator.run(DiagnosticMode.WITH_NETWORK, networkConsent = false)
        }.isFailure)
        assertEquals(0, requestCount)
        val permitted = coordinator.run(DiagnosticMode.WITH_NETWORK, networkConsent = true)
        assertEquals(6, requestCount)
        assertTrue(permitted.findings.all { it.state == DiagnosticState.OK })
    }

    @Test fun anonymousSourceCheckDoesNotBecomeGlobalFailureForSingle403() = runBlocking {
        val sourceSpec = DiagnosticCheckSpec(
            "source.muzmo.http", DiagnosticComponent.SOURCES, DiagnosticImpact.OPTIONAL,
            DiagnosticScope.NETWORK)
        val requiredSpec = DiagnosticCheckSpec(
            "queue.snapshot", DiagnosticComponent.DOWNLOAD_QUEUE, DiagnosticImpact.REQUIRED)
        val probes = listOf(
            object : DiagnosticProbe {
                override val spec = requiredSpec
                override suspend fun check(): DiagnosticOutcome =
                    DiagnosticOutcome(DiagnosticState.OK, DiagnosticEvidence.VERIFIED,
                        "Состояние очереди успешно прочитано.")
            },
            object : DiagnosticProbe {
                override val spec = sourceSpec
                override suspend fun check(): DiagnosticOutcome = SourceDiagnosticRules.http(403)
            },
        )
        val result = DiagnosticCoordinator(probes, clockMillis = { 100L })
            .run(DiagnosticMode.WITH_NETWORK, networkConsent = true)
        assertEquals(DiagnosticState.OK,
            DiagnosticAssessment.application(probes.map { it.spec }, result.findings, 101L).state)
        assertEquals(DiagnosticState.UNAVAILABLE,
            DiagnosticAssessment.component(DiagnosticComponent.SOURCES,
                probes.map { it.spec }, result.findings, 101L).state)
        assertEquals("HTTP_ACCESS_DENIED", result.findings[1].reasonCode)
    }
}
