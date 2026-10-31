package com.domenota.medialoader.core.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCoordinatorTest {
    @Test fun executionOrderMatchesVisibleCategoriesAndPreservesWithinCategoryOrder() = runBlocking {
        val processed = mutableListOf<String>()
        fun record(name: String, component: DiagnosticComponent): DiagnosticProbe =
            object : DiagnosticProbe {
                override val spec = DiagnosticCheckSpec(name, component, DiagnosticImpact.OPTIONAL)
                override suspend fun check(): DiagnosticOutcome {
                    processed += name
                    return DiagnosticOutcome(DiagnosticState.OK, DiagnosticEvidence.VERIFIED, "Проверено")
                }
            }
        val coordinator = DiagnosticCoordinator(listOf(
            record("queue.test", DiagnosticComponent.DOWNLOAD_QUEUE),
            record("runtime.test", DiagnosticComponent.MEDIA_RUNTIME),
            record("storage.test", DiagnosticComponent.STORAGE),
            record("source.first", DiagnosticComponent.SOURCES),
            record("editor.test", DiagnosticComponent.PHOTO_EDITOR),
            record("source.second", DiagnosticComponent.SOURCES),
        ), clockMillis = { 1000L })
        val expected = listOf("source.first", "source.second", "runtime.test",
            "queue.test", "storage.test", "editor.test")
        assertEquals(expected, coordinator.specifications.map { it.code })
        val emitted = mutableListOf<String>()
        coordinator.run(onFinding = { emitted += it.spec.code })
        assertEquals(expected, processed)
        assertEquals(expected, emitted)
    }

    @Test fun fullOnlyProbeIsOmittedFromQuickRuns() = runBlocking {
        var calls = 0
        val full = object : DiagnosticProbe {
            override val spec = DiagnosticCheckSpec("runtime.active", DiagnosticComponent.MEDIA_RUNTIME,
                DiagnosticImpact.OPTIONAL, scope = DiagnosticScope.FULL)
            override suspend fun check(): DiagnosticOutcome {
                calls++
                return DiagnosticOutcome(DiagnosticState.OK, DiagnosticEvidence.VERIFIED, "Запущено")
            }
        }
        val coordinator = DiagnosticCoordinator(listOf(full))
        assertEquals(0, coordinator.run(DiagnosticMode.LOCAL).findings.size)
        assertEquals(0, coordinator.run(DiagnosticMode.WITH_NETWORK, true).findings.size)
        assertEquals(0, calls)
        assertTrue(runCatching { coordinator.run(DiagnosticMode.FULL) }.isFailure)
        assertEquals(1, coordinator.run(DiagnosticMode.FULL, true).findings.size)
        assertEquals(1, calls)
    }
    private fun probe(
        name: String,
        scope: DiagnosticScope = DiagnosticScope.LOCAL,
        timeout: Long = 1_000L,
        action: suspend () -> DiagnosticOutcome,
    ): DiagnosticProbe = object : DiagnosticProbe {
        override val spec = DiagnosticCheckSpec(name, DiagnosticComponent.SOURCES,
            DiagnosticImpact.REQUIRED, scope = scope, timeoutMillis = timeout)
        override suspend fun check(): DiagnosticOutcome = action()
    }

    private fun success() = DiagnosticOutcome(DiagnosticState.OK,
        DiagnosticEvidence.VERIFIED, "Проверено.")

    @Test fun localRunNeverInvokesNetworkProbe() = runBlocking {
        var internetCalls = 0
        var localCalls = 0
        val coordinator = DiagnosticCoordinator(listOf(
            probe("source.local") { localCalls++; success() },
            probe("source.network", DiagnosticScope.NETWORK) {
                internetCalls++
                success()
            },
        ), clockMillis = { 1_000L })
        val result = coordinator.run()
        assertEquals(1, localCalls)
        assertEquals(0, internetCalls)
        assertEquals(DiagnosticState.OK, result.findings[0].state)
        assertEquals(DiagnosticState.UNCHECKED, result.findings[1].state)
    }

    @Test fun networkRequiresConsentEvenWithExplicitMode() = runBlocking {
        var calls = 0
        val coordinator = DiagnosticCoordinator(listOf(
            probe("source.network", DiagnosticScope.NETWORK) { calls++; success() },
        ))
        assertTrue(runCatching { coordinator.run(DiagnosticMode.WITH_NETWORK) }.isFailure)
        assertEquals(0, calls)
        val finished = coordinator.run(DiagnosticMode.WITH_NETWORK, networkConsent = true)
        assertEquals(1, calls)
        assertEquals(DiagnosticState.OK, finished.findings.single().state)
    }

    @Test fun timeoutMeansUnavailableNotBrokenComponent() = runBlocking {
        val coordinator = DiagnosticCoordinator(listOf(
            probe("source.slow", timeout = 20L) {
                delay(200L)
                success()
            },
        ))
        val result = coordinator.run().findings.single()
        assertEquals(DiagnosticState.UNAVAILABLE, result.state)
        assertEquals("CHECK_TIMEOUT", result.reasonCode)
        assertFalse(result.evidence == DiagnosticEvidence.VERIFIED)
    }

    @Test fun exceptionsDoNotLeakSecretsOrRaiseFalseRedError() = runBlocking {
        val coordinator = DiagnosticCoordinator(listOf(
            probe("source.fail") {
                throw IllegalStateException("access_token=private123 at https://test.invalid")
            },
        ))
        val found = coordinator.run().findings.single()
        assertEquals(DiagnosticState.UNAVAILABLE, found.state)
        assertEquals("CHECK_EXCEPTION", found.reasonCode)
        assertFalse(found.explanation.contains("private123"))
        assertFalse(found.explanation.contains("test.invalid"))
    }

    @Test fun concurrentRunsAreRejectedWithoutCancellingActiveCheck() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = DiagnosticCoordinator(listOf(
            probe("source.wait") {
                started.complete(Unit)
                release.await()
                success()
            },
        ))
        val first = async { coordinator.run() }
        started.await()
        try {
            val secondAttempt = runCatching { coordinator.run() }
            assertTrue(secondAttempt.exceptionOrNull() is DiagnosticAlreadyRunningException)
        } finally {
            release.complete(Unit)
        }
        assertEquals(DiagnosticState.OK, first.await().findings.single().state)
    }

    @Test fun cancellationStopsOnlyDiagnosticAndReleasesLock() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val coordinator = DiagnosticCoordinator(listOf(
            probe("source.cancel") {
                calls++
                if (calls == 1) {
                    started.complete(Unit)
                    delay(10_000L)
                }
                success()
            },
        ))
        val first = async { coordinator.run() }
        started.await()
        first.cancelAndJoin()
        assertTrue(first.isCancelled)
        val next = coordinator.run()
        assertEquals(2, calls)
        assertEquals(DiagnosticState.OK, next.findings.single().state)
    }

    @Test fun duplicateStableCodesAreRejected() {
        assertTrue(runCatching {
            DiagnosticCoordinator(listOf(probe("source.same") { success() },
                probe("source.same") { success() }))
        }.isFailure)
    }
}
