package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class VisibilityRecoveryPlannerTest {
    private fun member(id: String = "one") = VisibilityMoveRecord(
        "op-$id", "folder", id, VisibilityDirection.SHOW,
        "file:///hidden/$id", "$id.jpg", 1_700_000_000_000L,
        VisibilityFingerprint(4,"a".repeat(64)), "token-$id")
    private fun batch(first: VisibilityMoveRecord = member(), next: Int = 0) =
        VisibilityBatchRecord("batch", "folder", VisibilityDirection.SHOW,
            listOf(first,member("two")), next)

    @Test fun cleanAndIntermediateLegacyCheckpointsAreReadOnlyAndReplayable() {
        assertNull(VisibilityRecoveryPlanner.inspect(null,null))
        val first = member()
        for(phase in listOf(VisibilityMovePhase.PREPARED,VisibilityMovePhase.ALLOCATED,
            VisibilityMovePhase.VERIFIED,VisibilityMovePhase.LINKED,VisibilityMovePhase.CLEANED)) {
            val ongoing = first.copy(phase=phase,
                destinationUri=if(phase==VisibilityMovePhase.PREPARED) null else "content://media/owned")
            val plan=VisibilityRecoveryPlanner.inspect(batch(),ongoing)
            assertEquals(0,plan?.confirmed)
            assertEquals(2,plan?.total)
            assertEquals(ongoing,plan?.active)
        }
        assertEquals(1,VisibilityRecoveryPlanner.inspect(batch(next=1),null)?.confirmed)
        assertEquals(2,VisibilityRecoveryPlanner.inspect(batch(next=2),null)?.confirmed)
    }

    @Test fun orphanOrWrongMemberFailsClosedBeforeMutation() {
        val first = member()
        assertTrue(runCatching {
            VisibilityRecoveryPlanner.inspect(null,first.copy(
                phase=VisibilityMovePhase.ALLOCATED,destinationUri="owned"))
        }.isFailure)
        assertTrue(runCatching {
            VisibilityRecoveryPlanner.inspect(batch(next=2),first.copy(
                phase=VisibilityMovePhase.ALLOCATED,destinationUri="owned"))
        }.isFailure)
        assertTrue(runCatching {
            VisibilityRecoveryPlanner.inspect(batch(),member("two").copy(
                phase=VisibilityMovePhase.ALLOCATED,destinationUri="owned"))
        }.isFailure)
        assertTrue(runCatching {
            VisibilityRecoveryPlanner.inspect(batch(),first.copy(
                fingerprint=VisibilityFingerprint(4,"b".repeat(64)),
                phase=VisibilityMovePhase.ALLOCATED,destinationUri="owned"))
        }.isFailure)
    }

    @Test fun batchFirstMetadataUpgradeIsOnlyAllowedForMatchingOldHideIdentity() {
        val origin=VisibilityPublicOrigin(
            "content://media/external_primary/images/media/1","external_primary",
            "Pictures/","one.jpg","image/jpeg",
            "/storage/emulated/0/Pictures/one.jpg",
            4,1_700_000_000L,1_700_000_000L,1_700_000_000_000L,null)
        val old=member().copy(direction=VisibilityDirection.HIDE,
            sourceUri=origin.uri,sourceName=origin.name,
            sourceModifiedMillis=origin.modifiedMillis)
        val new=old.copy(publicOrigin=origin)
        val saved=VisibilityBatchRecord("batch","folder",VisibilityDirection.HIDE,listOf(new))
        val pending=old.copy(phase=VisibilityMovePhase.ALLOCATED,destinationUri="file:///hidden/owned")
        assertTrue(runCatching { VisibilityRecoveryPlanner.inspect(saved,pending) }.isFailure)
        assertEquals(pending,VisibilityRecoveryPlanner.inspect(saved,pending,true)?.active)
        assertTrue(runCatching { VisibilityRecoveryPlanner.inspect(saved,pending.copy(
            fingerprint=VisibilityFingerprint(4,"b".repeat(64))),true) }.isFailure)
    }
}
