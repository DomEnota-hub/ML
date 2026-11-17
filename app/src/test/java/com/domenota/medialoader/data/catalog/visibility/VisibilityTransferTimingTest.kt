package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.assertEquals
import org.junit.Test

class VisibilityTransferTimingTest {
    @Test fun elapsedUsesMonotonicNanosecondsAndClampsBadOrder() {
        assertEquals(123L,visibilityDurationMs(500_000_000L,623_999_999L))
        assertEquals(0L,visibilityDurationMs(500_000_000L,100L))
        assertEquals(0L,visibilityDurationMs(123L,123L))
    }
}
