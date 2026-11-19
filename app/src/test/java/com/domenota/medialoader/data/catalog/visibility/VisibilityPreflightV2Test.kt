package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class VisibilityPreflightV2Test {
    private fun member(id: Int) = VisibilityMoveRecord(
        "operation-$id","folder","id-$id",VisibilityDirection.HIDE,
        "content://media/external_primary/images/media/$id","file-$id.jpg",
        1_700_000_000_000L,VisibilityFingerprint(100L+id,"a".repeat(64)),"token-$id")
    private fun preview(count:Int, failed:Int=0) = visibilityPreflightSummarize(
        "request-token","folder",true,count,
        (1..(count-failed)).map(::member),List(failed){VisibilityPreflightIssue.SOURCE})

    @Test fun snapshotsSupportExactBatches1_8_46() {
        for(count in listOf(1,8,46)) {
            val p=preview(count)
            assertEquals(count,p.summary.ready);assertEquals(count,p.summary.total)
            assertEquals(0,p.summary.problems);assertTrue(p.summary.canStart)
            val batch=checkNotNull(p.verifiedBatch)
            assertTrue(visibilityPreflightMatches(batch,batch))
            assertTrue(visibilityPreflightMatches(batch,batch.copy(
                id="different-random-batch",members=batch.members.mapIndexed{index,r->
                    r.copy(operationId="new-$index",destinationToken="other-$index")
                })))
        }
    }
    @Test fun anyFailedSourceBlocksTheEntireBatch() {
        for(count in listOf(1,8,46)) {
            val p=preview(count,1)
            assertFalse(p.summary.canStart);assertEquals(1,p.summary.problems)
            assertEquals(1,p.summary.issueCounts[VisibilityPreflightIssue.SOURCE])
            assertNull(p.verifiedBatch)
            assertFalse(visibilityPreflightMatches(p.verifiedBatch,
                VisibilityBatchRecord("other","folder",VisibilityDirection.HIDE,listOf(member(1)))))
        }
    }
    @Test fun changedSourceOrFingerprintInvalidatesApprovedPreview() {
        val batch=checkNotNull(preview(8).verifiedBatch)
        assertFalse(visibilityPreflightMatches(batch,batch.copy(members=batch.members.mapIndexed{i,r->
            if(i==3)r.copy(sourceUri="content://media/external_primary/images/media/999")else r})))
        assertFalse(visibilityPreflightMatches(batch,batch.copy(members=batch.members.mapIndexed{i,r->
            if(i==3)r.copy(fingerprint=VisibilityFingerprint(r.fingerprint.bytes,"b".repeat(64)))else r})))
        assertFalse(visibilityPreflightMatches(batch,batch.copy(members=batch.members.reversed())))
    }
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
