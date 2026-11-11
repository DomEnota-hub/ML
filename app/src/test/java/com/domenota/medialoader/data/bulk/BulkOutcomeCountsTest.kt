package com.domenota.medialoader.data.bulk

import org.junit.Assert.assertEquals
import org.junit.Test

class BulkOutcomeCountsTest {
    @Test fun reports56SelectedButOnly2ActuallyQueuedWithoutClaimingFilesLost() {
        val counts = BulkOutcomeCounts.from(
            selected = 56, queued = 2, completed = 2, failed = 0, cancelled = 0)
        assertEquals(56, counts.selected)
        assertEquals(2, counts.queued)
        assertEquals(2, counts.terminal)
        assertEquals(54, counts.neverQueued)
        assertEquals(0, counts.pendingOrUnknown)
    }

    @Test fun distinguishesPendingFromFailedAndCancelled() {
        val counts = BulkOutcomeCounts.from(
            selected = 8, queued = 7, completed = 2, failed = 1, cancelled = 1)
        assertEquals(4, counts.terminal)
        assertEquals(3, counts.pendingOrUnknown)
        assertEquals(1, counts.neverQueued)
    }

    @Test fun excessStaleSnapshotDoesNotProduceNegativeCounts() {
        val counts = BulkOutcomeCounts.from(
            selected = 1, queued = 1, completed = 2, failed = 0, cancelled = 0)
        assertEquals(0, counts.pendingOrUnknown)
        assertEquals(0, counts.neverQueued)
    }
}
