package com.domenota.medialoader.ui

import org.junit.Assert.*
import org.junit.Test

class AppReferencePublicationPolicyTest {
    @Test fun coldBootstrapKeepsFirstPageTimedProgressAndCompleteGeneration() {
        val policy = AppReferencePublicationPolicy()
        assertTrue(policy.shouldPublish(16, 751, true, 0))
        assertFalse(policy.shouldPublish(32, 751, false, 249))
        assertTrue(policy.shouldPublish(48, 751, false, 250))
        assertTrue(policy.shouldPublish(751, 751, false, 0))
        policy.completed()
        assertFalse(policy.shouldPublish(16, 751, true, 10_000))
    }
    @Test fun warmRebuildEmitsOnlyOneCompleteCatalogIncludingGenuineFileChange() {
        val policy = AppReferencePublicationPolicy().apply { completed() }
        for (total in listOf(751, 752, 750, 11_240 + 3_190)) {
            val emissions = mutableListOf<List<String>>()
            for (resolved in (16 until total step 16) + total) {
                if (policy.shouldPublish(resolved, total, resolved == 16, 1_000)) {
                    emissions += List(resolved) { if (it == 0 && total == 752) "changed-file" else "synthetic:$it" }
                    if (resolved == total) policy.completed()
                }
            }
            assertEquals(1, emissions.size)
            assertEquals(total, emissions.single().size)
            if (total == 752) assertEquals("changed-file", emissions.single().first())
        }
    }
    @Test fun cancellationCannotPromoteColdPassOrPublishWarmPartialAfterRecoveryClearsSnapshot() {
        val cold = AppReferencePublicationPolicy()
        assertTrue(cold.shouldPublish(16, 751, true, 0))
        // Cold build was cancelled before a complete emit; a fresh first page remains allowed.
        assertTrue(cold.shouldPublish(16, 751, true, 0))
        cold.completed()
        var published: Int? = null // recovery/isRunning still clears the actual reference flow.
        for (resolved in listOf(16, 32, 48)) {
            if (cold.shouldPublish(resolved, 751, resolved == 16, 10_000)) published = resolved
        }
        assertNull(published) // cancelled warm pass never exposes a partial recovery catalog.
        if (cold.shouldPublish(752, 752, false, 0)) published = 752
        assertEquals(752, published)
    }
}
