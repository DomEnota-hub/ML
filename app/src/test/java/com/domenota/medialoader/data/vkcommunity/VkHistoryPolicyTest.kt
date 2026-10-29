package com.domenota.medialoader.data.vkcommunity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VkHistoryPolicyTest {
    @Test fun activeJobsCannotBeDeleted() {
        assertFalse(VkHistoryPolicy.canDelete(VkJobPhase.SCANNING))
        assertFalse(VkHistoryPolicy.canDelete(VkJobPhase.DOWNLOADING))
        assertTrue(VkHistoryPolicy.canDelete(VkJobPhase.READY))
        assertTrue(VkHistoryPolicy.canDelete(VkJobPhase.FINISHED))
    }
}
