package com.domenota.medialoader.data.library

import org.junit.Assert.*
import org.junit.Test

class PhoneScanCheckpointPolicyTest {
    @Test fun smallCollectionsAreNeverRewrittenBeforeFirstPage() {
        val policy = PhoneScanCheckpointPolicy()
        assertFalse(policy.shouldSave(0))
        assertFalse(policy.shouldSave(63))
        assertTrue(policy.shouldSave(64))
        assertFalse(policy.shouldSave(64))
        assertFalse(policy.shouldSave(1800))
        assertTrue(policy.shouldSave(2112))
        assertFalse(policy.shouldSave(3000))
        assertTrue(policy.shouldSave(4160))
    }

    @Test fun tenThousandItemLibraryRequiresOnlyOccasionalCheckpoints() {
        val policy = PhoneScanCheckpointPolicy()
        val writes = (0..11_200 step 64).count(policy::shouldSave)
        assertTrue(writes <= 7)
        assertTrue(writes >= 4)
    }

    @Test fun policySupportsCustomPageAndInterval() {
        val policy = PhoneScanCheckpointPolicy(firstPageSize = 2, interval = 10)
        assertFalse(policy.shouldSave(1))
        assertTrue(policy.shouldSave(2))
        assertFalse(policy.shouldSave(11))
        assertTrue(policy.shouldSave(12))
    }
}
