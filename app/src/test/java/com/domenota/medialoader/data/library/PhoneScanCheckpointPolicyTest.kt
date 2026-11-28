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

    @Test fun firstPageIsStillImmediateButLaterPublicationsAreConflated() {
        val policy = PhoneScanProgressPolicy(firstPageSize = 4, minRowsSincePublication = 8, minIntervalMs = 3000)
        assertFalse(policy.shouldPublish(3, 0))
        assertTrue(policy.shouldPublish(4, 50))
        assertFalse(policy.shouldPublish(12, 500))
        assertFalse(policy.shouldPublish(11, 3100))
        assertTrue(policy.shouldPublish(12, 3100))
        assertFalse(policy.shouldPublish(12, 9000))
        assertTrue(policy.shouldPublish(20, 9100))
    }

    @Test fun largeColdGalleryDoesNotPublishEverySecond() {
        val policy = PhoneScanProgressPolicy()
        // 11.5k files indexed over 57 seconds: a plausible slow Android MediaStore.
        val emissions = (1..11_453).count { count ->
            policy.shouldPublish(count, count.toLong() * 57_100 / 11_453)
        }
        assertTrue("First page plus bounded updates, not 57 partial grids", emissions <= 20)
        assertTrue("Keep visible progress during a long scan", emissions >= 12)
    }

    @Test fun permissionsStayCheckedByCallerAndProgressHasNoHiddenFinalState() {
        val policy = PhoneScanProgressPolicy(firstPageSize = 2, minRowsSincePublication = 3, minIntervalMs = 0)
        assertTrue(policy.shouldPublish(2, 0))
        assertFalse(policy.shouldPublish(4, 1))
        assertTrue(policy.shouldPublish(5, 2))
        // Complete snapshots do not depend on this policy and must still publish.
    }

    @Test fun policySupportsCustomPageAndInterval() {
        val policy = PhoneScanCheckpointPolicy(firstPageSize = 2, interval = 10)
        assertFalse(policy.shouldSave(1))
        assertTrue(policy.shouldSave(2))
        assertFalse(policy.shouldSave(11))
        assertTrue(policy.shouldSave(12))
    }
}
