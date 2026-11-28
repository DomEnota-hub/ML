package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class VisibilityPreflightV2Test {
    private fun member(id: Int) = VisibilityMoveRecord(
        "operation-$id","folder","id-$id",VisibilityDirection.HIDE,
        "content://media/external_primary/images/media/$id","file-$id.jpg",
        1_700_000_000_000L,VisibilityFingerprint(100L+id,"a".repeat(64)),"token-$id")
    @Test fun errorsAreTypedAndPresentationNeverIncludesSourceData() {
        for(kind in VisibilityPlanSizeMismatch.entries)
            assertTrue(visibilityPreflightIssue(VisibilityPlanSizeFailure(kind)).name.isNotBlank())
        assertEquals(VisibilityPreflightIssue.ACCESS,visibilityPreflightIssue(SecurityException("/private")))
        assertEquals(VisibilityPreflightIssue.SOURCE,visibilityPreflightIssue(java.io.IOException("/private")))
        val p=visibilityPreflightSummarize("token","folder",true,8,(1..6).map(::member),
            listOf(VisibilityPreflightIssue.ACCESS,VisibilityPreflightIssue.METADATA))
        assertEquals(6,p.summary.ready);assertEquals(2,p.summary.problems)
        assertNull(p.verifiedBatch)
        assertFalse(p.summary.toString().contains("content://"))
    }
}
