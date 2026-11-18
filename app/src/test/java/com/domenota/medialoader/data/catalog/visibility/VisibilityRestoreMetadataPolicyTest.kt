package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class VisibilityRestoreMetadataPolicyTest {
    private fun status(
        bytes: Long = 421L,
        modified: Long = 1_700_000_001L,
        taken: Long? = 1_700_000_000_000L,
    ) = VisibilityRestoreMetadataPolicy.observe(
        expectedBytes = 421L,
        expectedModifiedSeconds = 1_700_000_001L,
        expectedTakenMillis = 1_700_000_000_000L,
        observedBytes = bytes,
        observedModifiedSeconds = modified,
        observedTakenMillis = taken,
    )

    @Test fun exactAndOneSecondDateTakenRoundingAreAccepted() {
        assertTrue(status().confirmed)
        assertTrue(status(taken=1_700_000_001_000L).confirmed)
        assertTrue(status(taken=1_699_999_999_000L).confirmed)
        assertEquals("OK", status().diagnosticCode)
    }

    @Test fun missingOrExcessiveDateTakenDriftMustNotAuthorizeCleanup() {
        assertFalse(status(taken=null).confirmed)
        assertEquals("DATE_TAKEN",status(taken=null).diagnosticCode)
        assertFalse(status(taken=1_700_000_001_001L).confirmed)
        assertFalse(status(taken=1_699_999_998_999L).confirmed)
    }

    @Test fun bytesAndModifiedMustStillMatchExactly() {
        assertFalse(status(bytes=420L).confirmed)
        assertFalse(status(modified=1_700_000_002L).confirmed)
        assertEquals("SIZE+DATE_MODIFIED+DATE_TAKEN",
            status(bytes=419L,modified=1_700_000_005L,taken=null).diagnosticCode)
    }

    @Test fun originalWithNoDateTakenNeverInventsRequiredTimestamp() {
        val observation = VisibilityRestoreMetadataPolicy.observe(
            1_000L,1_700_000_000L,null,1_000L,1_700_000_000L,null)
        assertTrue(observation.confirmed)
        assertTrue(VisibilityRestoreMetadataPolicy.observe(
            1_000L,1_700_000_000L,null,1_000L,1_700_000_000L,999L).confirmed)
    }
}
