package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class FolderVisibilityReadPolicyTest {
    private fun receipt(id: String = "phone:image:42", folder: String = "a", direction: VisibilityDirection = VisibilityDirection.HIDE): VisibilityMoveRecord {
        val origin = VisibilityPublicOrigin("content://media/external_primary/images/media/42", "external_primary", "Pictures/ML/", "a.jpg", "image/jpeg", "/storage/emulated/0/Pictures/ML/a.jpg", 3, 10, 9, null, null)
        val hidden = "file:///storage/emulated/0/Documents/MediaLoaderHidden/1234567890123456/a.jpg"
        return VisibilityMoveRecord("op",folder,id,direction,
            if (direction == VisibilityDirection.HIDE) origin.uri else hidden,"a.jpg",10000,
            VisibilityFingerprint(3,"a".repeat(64)),"1234567890123456",
            if (direction == VisibilityDirection.HIDE) hidden else "content://media/external_primary/images/media/51",
            VisibilityMovePhase.CLEANED,origin)
    }
    @Test fun explicitFolderRequiresExactReceiptAndMembership() {
        val rec = receipt(); val policy = FolderVisibilityReadPolicy(FolderVisibilitySnapshot(setOf("a"),listOf(rec)))
        assertTrue(policy.isExcluded(rec.downloadId,"a",rec.destinationUri))
        assertTrue(policy.canOpenHidden(rec.downloadId,"a",rec.destinationUri,"a"))
        assertFalse(policy.canOpenHidden(rec.downloadId,"a",rec.destinationUri,"b"))
        assertFalse(policy.canOpenHidden(rec.downloadId,"a",rec.sourceUri,"a"))
        assertFalse(policy.canOpenHidden("another","a",rec.destinationUri,"a"))
        assertFalse(policy.canOpenHidden(rec.downloadId,"b",rec.destinationUri,"a"))
    }
    @Test fun pendingFolderIsExcludedEvenBeforeModeCommit() {
        val policy = FolderVisibilityReadPolicy(FolderVisibilitySnapshot(),pendingFolder="a")
        assertTrue(policy.isExcluded("x","a","content://x"))
        assertFalse(policy.canOpenHidden("x","a","content://x","a"))
        assertFalse(policy.isExcluded("x","b","content://x"))
    }
    @Test fun receiptAlonePreventsLeakDuringInterruptedCommit() {
        val rec=receipt(); val policy=FolderVisibilityReadPolicy(FolderVisibilitySnapshot(receipts=listOf(rec)))
        assertTrue(policy.isExcluded(rec.downloadId,null,rec.destinationUri))
        assertTrue(policy.isExcluded("different-alias",null,rec.destinationUri))
        assertFalse(policy.canOpenHidden(rec.downloadId,"a",rec.destinationUri,"a"))
    }
    @Test fun recycledPhoneIdNeverResolvesRetainedHiddenIdentity() {
        val rec=receipt(); val policy=FolderVisibilityReadPolicy(FolderVisibilitySnapshot(setOf("a"),listOf(rec)))
        assertTrue(policy.isRetiredPhoneCollision("phone:image:42","content://other/42",emptySet()))
        assertTrue(policy.isRetiredPhoneCollision("new-id",rec.sourceUri,emptySet()))
        assertFalse(policy.isRetiredPhoneCollision("phone:image:43","content://media/external_primary/images/media/43",emptySet()))
    }
    @Test fun restoredExactAddressCanBeAdmittedWithoutReusingOldId() {
        val rec=receipt(direction=VisibilityDirection.SHOW); val policy=FolderVisibilityReadPolicy(FolderVisibilitySnapshot(receipts=listOf(rec)))
        assertFalse(policy.isExcluded(rec.downloadId,"a",rec.destinationUri))
        assertTrue(policy.isRetiredPhoneCollision("phone:image:42",rec.destinationUri,emptySet()))
        assertFalse(policy.isRetiredPhoneCollision("phone:image:42",rec.destinationUri,emptySet(),confirmedCurrent=true))
    }
    @Test fun corruptOrUnidentifiedPendingFailsClosed() {
        val policy=FolderVisibilityReadPolicy(FolderVisibilitySnapshot(),unknownPending=true)
        assertTrue(policy.isExcluded("x",null,null))
        assertFalse(policy.canOpenHidden("x","a","file:///x","a"))
    }
}
