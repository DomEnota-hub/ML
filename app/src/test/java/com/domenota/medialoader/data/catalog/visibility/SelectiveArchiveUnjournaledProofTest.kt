package com.domenota.medialoader.data.catalog.visibility

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SelectiveArchiveUnjournaledProofTest {
    private val hash="a".repeat(64)
    private val links="b".repeat(64)
    private fun identity(i:Int)=SelectiveArchiveIdentity(
        "download-$i","content://media/external/audio/media/$i",i.toLong(),
        "external_primary","Music/Shared/","track-$i.mp3",(100+i).toLong(),
        hash,1_700_000_000_000L+i,links
    )
    private fun state(x:SelectiveArchiveIdentity)=SelectiveArchiveMediaState(
        x.originalUri,x.mediaStoreId,x.volume,x.relativePath,x.displayName,
        x.sizeBytes,x.modifiedAtMillis,"audio/mpeg",false,false
    )
    private class Fake(
        var initial:SelectiveArchiveMediaState?,
        var next:SelectiveArchiveMediaState?=initial,
        var payload:SelectiveArchivePayloadDigest?=initial?.let {
            SelectiveArchivePayloadDigest(it.bytes,"a".repeat(64))
        },
    ):SelectiveArchiveMediaProbe {
        var inspections=0
        override suspend fun inspect(uri:String):SelectiveArchiveMediaState? {
            inspections++
            return if(inspections==1)initial else next
        }
        override suspend fun fingerprint(uri:String)=payload
    }

    @Test fun missingReceiptAcceptedOnlyWhenSamePublicOriginalCanBeReadTwice()=runBlocking<Unit> {
        val original=identity(1)
        val probe=Fake(state(original))
        assertTrue(SelectiveArchiveUnjournaledProof(probe){ links }.publicUnchanged(original))
        assertEquals(2,probe.inspections)
    }

    @Test fun staleIdTruncationHiddenStatusRevokedReferenceOrScanRaceNeverCountsAsUntouched()=runBlocking<Unit> {
        val original=identity(1)
        val bad=listOf(
            Fake(state(original).copy(mediaStoreId=8)),
            Fake(state(original).copy(bytes=1)),
            Fake(state(original).copy(pending=true)),
            Fake(state(original).copy(trashed=true)),
            Fake(state(original),state(original).copy(relativePath="Music/Other/")),
            Fake(null),
            Fake(state(original),payload=SelectiveArchivePayloadDigest(101,"c".repeat(64))),
        )
        for(probe in bad) {
            assertFalse(SelectiveArchiveUnjournaledProof(probe){ links }.publicUnchanged(original))
        }
        assertFalse(SelectiveArchiveUnjournaledProof(Fake(state(original))){"c".repeat(64)}
            .publicUnchanged(original))
        assertFalse(SelectiveArchiveUnjournaledProof(Fake(state(original))){null}
            .publicUnchanged(original))
    }

    @Test fun partialSixMemberBatchWithFourUntouchedAndTwoReceiptsIsCompleteOnlyAfterProof() {
        val originals=(1..6).map(::identity)
        fun receipt(x:SelectiveArchiveIdentity)=SelectiveArchiveMoveReceipt(
            "virtual",x.catalogId,x.originalUri,x.sha256,"Music/Hidden/","T".repeat(32),
            SelectiveArchiveMovePhase.HIDDEN_VERIFIED,2L
        )
        val known=originals.take(2).associate { it.catalogId to receipt(it) }
        val intact=originals.drop(2).mapTo(mutableSetOf()){it.catalogId}
        assertTrue(selectiveArchiveRecoveryCoverage(originals,known,intact))
        assertFalse(selectiveArchiveRecoveryCoverage(originals,known,intact-originals.last().catalogId))
        val mismatched=known.toMutableMap().apply {
            this[originals[0].catalogId]=receipt(originals[0]).copy(originalSha256="c".repeat(64))
        }
        assertFalse(selectiveArchiveRecoveryCoverage(originals,mismatched,intact))
        assertFalse(selectiveArchiveRecoveryCoverage(originals,known+("unexpected" to receipt(originals[0])),intact))
    }

    @Test fun rollbackNeverClaimsSuccessUntilAllSixHaveIndependentProof() {
        val originals=(1..6).map(::identity)
        val restored=setOf("download-1","download-2")
        val untouched=(3..6).mapTo(mutableSetOf()) { "download-$it" }
        assertTrue(selectiveArchiveRollbackComplete(originals,restored,untouched))
        assertFalse(selectiveArchiveRollbackComplete(originals,restored,untouched-"download-6"))
        assertFalse(selectiveArchiveRollbackComplete(originals,
            restored,untouched+"download-1")) // A member cannot be counted twice.
        assertFalse(selectiveArchiveRollbackComplete(originals,restored,
            untouched+"unexpected"))
        assertFalse(selectiveArchiveRollbackComplete(originals,
            restored+"download-1",untouched-"download-6"))
        assertFalse(selectiveArchiveRollbackComplete(emptyList(),emptySet(),emptySet()))
    }

    @Test fun batchOutcomeNeverClaimsIncompleteRestoreSucceeded() {
        val original=identity(1)
        val receipt=SelectiveArchiveMoveReceipt(
            "virtual",original.catalogId,original.originalUri,hash,
            "Music/Hidden/","T".repeat(32),SelectiveArchiveMovePhase.RESTORED_VERIFIED,4
        )
        val good=SelectiveArchiveBatchRestoreResult(listOf(receipt),setOf("download-2"),2)
        assertEquals(2,good.expectedCount)
        assertThrows(IllegalArgumentException::class.java) {
            SelectiveArchiveBatchRestoreResult(listOf(receipt),emptySet(),2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectiveArchiveBatchRestoreResult(listOf(receipt),setOf("download-1"),2)
        }
    }
}
