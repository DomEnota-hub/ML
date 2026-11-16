package com.domenota.medialoader.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LatestProjectionCacheTest {
    @Test fun unchangedLargeSourceReusesExactSnapshotWhileCurrentPrivacyStillFilters() = runBlocking {
        val cache = LatestProjectionCache<Pair<Int, Int>, List<Int>>()
        var builds = 0
        suspend fun snapshot() = cache.get(1 to 1) { builds++; (0 until 11_240 + 3_190).toList() }
        val first = snapshot()
        val pending = setOf(0, 46, 11_240)
        val second = snapshot()
        assertSame(first, second)
        assertEquals(1, builds)
        assertEquals(14_427, second.filterNot { it in pending }.size)
        assertTrue(second.filterNot { it in pending }.none { it in pending })
    }
    @Test fun changedSourceOrAccessEpochBuildsAndOnlyLatestGenerationIsRetained() = runBlocking {
        val cache = LatestProjectionCache<Pair<Int, Int>, Any>()
        val first = cache.get(1 to 1) { Any() }
        val changed = cache.get(2 to 1) { Any() }
        assertNotSame(first, changed)
        assertNotSame(changed, cache.get(2 to 2) { Any() })
        assertNotSame(first, cache.get(1 to 1) { Any() })
    }
    @Test fun cancelledBuildCannotReplaceLastCompleteSnapshot() = runBlocking {
        val cache = LatestProjectionCache<Int, Any>()
        val first = cache.get(1) { Any() }
        try { cache.get(2) { throw CancellationException("synthetic") }; fail("must cancel") }
        catch (_: CancellationException) { }
        assertSame(first, cache.get(1) { error("unexpected rebuild") })
    }
}
