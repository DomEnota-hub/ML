package com.domenota.medialoader.ui.components

import org.junit.Assert.*
import org.junit.Test

class LocalThumbnailFailuresTest {
    @Test fun identicalFailuresWaitButCoverRevisionAndIdentityCanRetryImmediately() {
        var clock = 0L
        val cache = LocalThumbnailFailures(now = { clock })
        cache.failed("audio:revision0:identityA")
        assertTrue(cache.suppressed("audio:revision0:identityA"))
        assertFalse(cache.suppressed("audio:revision1:identityA"))
        assertFalse(cache.suppressed("audio:revision0:identityB"))
        clock = 29_999L
        assertTrue(cache.suppressed("audio:revision0:identityA"))
        clock = 30_000L
        assertFalse(cache.suppressed("audio:revision0:identityA"))
    }
    @Test fun largeCatalogRetainsOnlyBoundedRecentMisses() {
        val cache = LocalThumbnailFailures(now = { 0L })
        repeat(11_240 + 3_190) { cache.failed("synthetic:$it") }
        assertFalse(cache.suppressed("synthetic:0"))
        assertFalse(cache.suppressed("synthetic:${11_240 + 3_190 - 257}"))
        assertTrue(cache.suppressed("synthetic:${11_240 + 3_190 - 256}"))
        assertTrue(cache.suppressed("synthetic:${11_240 + 3_190 - 1}"))
    }
    @Test fun successfulRetryAndClockRollbackRemoveSuppression() {
        var clock = 100L
        val cache = LocalThumbnailFailures(now = { clock })
        cache.failed("a")
        cache.succeeded("a")
        assertFalse(cache.suppressed("a"))
        cache.failed("b")
        clock = 0L
        assertFalse(cache.suppressed("b"))
    }
}
