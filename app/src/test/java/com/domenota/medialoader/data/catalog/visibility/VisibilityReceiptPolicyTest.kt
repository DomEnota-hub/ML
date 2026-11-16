package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class VisibilityReceiptPolicyTest {
    private val origin = VisibilityPublicOrigin("content://media/external/images/media/17", "external_primary", "Pictures/", "photo.jpg",
        "image/jpeg", "/storage/emulated/0/Pictures/photo.jpg", 20, 1700000000, 1690000000, null,"app")
    private val hide = VisibilityMoveRecord("hide","folder","media",VisibilityDirection.HIDE,origin.uri,origin.name,
        origin.modifiedMillis,VisibilityFingerprint(origin.bytes,"a".repeat(64)),"0123456789abcdef","file:///hidden/photo.jpg",VisibilityMovePhase.CLEANED,origin)
    private val show = hide.copy(operationId="show",direction=VisibilityDirection.SHOW,sourceUri=hide.destinationUri!!,
        destinationUri="content://media/external/images/media/18",destinationToken="fedcba9876543210")
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalStateException) { } }
    @Test fun hideShowRoundTripAndReplayKeepOrigin() {
        VisibilityReceiptPolicy.validateTransition(null,hide); VisibilityReceiptPolicy.validateTransition(hide,hide)
        VisibilityReceiptPolicy.validateTransition(hide,show); VisibilityReceiptPolicy.validateTransition(show,show)
        assertEquals(hide,VisibilityReceiptPolicy.decode(VisibilityReceiptPolicy.encode(hide)))
        assertEquals(show,VisibilityReceiptPolicy.decode(VisibilityReceiptPolicy.encode(show)))
        assertTrue(VisibilityReceiptPolicy.confirms(hide,hide.copy(destinationUri=null,phase=VisibilityMovePhase.PREPARED)))
        assertFalse(VisibilityReceiptPolicy.confirms(show,hide.copy(destinationUri=null,phase=VisibilityMovePhase.PREPARED)))
    }
    @Test fun verifiedEditChangesBytesWithoutLosingOriginalPlacementOrLogicalIdentity() {
        val changed = VisibilityFingerprint(25,"b".repeat(64))
        val revised = VisibilityReceiptPolicy.afterVerifiedEdit(hide,hide.destinationUri!!,hide.fingerprint,changed,1700001000000L)
        assertEquals(hide.downloadId,revised.downloadId)
        assertEquals(hide.destinationUri,revised.destinationUri)
        assertEquals(origin.copy(bytes=25,modifiedSeconds=1700001000L),revised.publicOrigin)
        assertEquals(changed,revised.fingerprint)
        val nextShow = show.copy(fingerprint=changed,publicOrigin=revised.publicOrigin,sourceModifiedMillis=revised.sourceModifiedMillis)
        VisibilityReceiptPolicy.validateTransition(revised,nextShow)
        rejects { VisibilityReceiptPolicy.afterVerifiedEdit(hide,"file:///other.jpg",hide.fingerprint,changed,1700001000000L) }
        rejects { VisibilityReceiptPolicy.afterVerifiedEdit(hide,hide.destinationUri!!,changed,changed,1700001000000L) }
        rejects { VisibilityReceiptPolicy.afterVerifiedEdit(show,show.destinationUri!!,show.fingerprint,changed,1700001000000L) }
    }
    @Test fun noShowWithoutVerifiedHideOrChangedOriginalPlacement() {
        rejects { VisibilityReceiptPolicy.validateTransition(null,show) }
        rejects { VisibilityReceiptPolicy.validateTransition(hide,show.copy(publicOrigin=origin.copy(owner="other"))) }
        rejects { VisibilityReceiptPolicy.validateTransition(hide,show.copy(sourceUri="file:///another.jpg")) }
        rejects { VisibilityReceiptPolicy.validateTransition(hide,show.copy(folderId="different")) }
    }
}
