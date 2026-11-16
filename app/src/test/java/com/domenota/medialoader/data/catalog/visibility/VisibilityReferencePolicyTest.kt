package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class VisibilityReferencePolicyTest {
    private val record = VisibilityMoveRecord("op","folder","file",VisibilityDirection.HIDE,
        "content://media/external_primary/images/media/17","photo.jpg",123L,
        VisibilityFingerprint(12,"a".repeat(64)),"token",catalogSourceUri="file:///storage/emulated/0/Pictures/photo.jpg")

    @Test fun logicalAliasDoesNotAuthorizeAnUnrelatedLocator() {
        val linked = record.copy(destinationUri="file:///hidden/photo.jpg",phase=VisibilityMovePhase.LINKED)
        assertEquals(record.sourceUri,VisibilityReferencePolicy.currentUri(record,record.logicalSourceUri))
        assertNull(VisibilityReferencePolicy.currentUri(record,record.sourceUri))
        assertNull(VisibilityReferencePolicy.currentUri(record,"content://media/external_primary/images/media/18"))
        assertEquals(linked.destinationUri,VisibilityReferencePolicy.currentUri(linked,linked.destinationUri))
        assertEquals(linked,VisibilityJournalCodec.decode(VisibilityJournalCodec.encode(linked)))
    }
    @Test fun collectionReplacementPreservesStableIdentityButRetiresOldPhysicalKeys() {
        val old = setOf("app:file","file-stat:1:2:12:1","locator:old")
        val new = setOf("app:file","file-stat:1:3:12:1","locator:new")
        assertEquals(new,VisibilityReferencePolicy.replacementKeys(old,old,new))
        assertEquals(new,VisibilityReferencePolicy.replacementKeys(setOf("app:file"),old,new))
        assertNull(VisibilityReferencePolicy.replacementKeys(setOf("app:other"),old,new))
    }
    @Test fun conflictingAppIdentityOrRecycledPhysicalLocatorCannotRebind() {
        val old = setOf("file-stat:1:2:12:1","locator:old")
        val new = setOf("file-stat:1:3:12:1","locator:new")
        for (conflict in listOf(setOf("app:other","locator:old"),setOf("file-stat:1:999:12:1","locator:old"))) {
            try { VisibilityReferencePolicy.replacementKeys(conflict,old,new); fail("Conflicting identity") }
            catch (_:IllegalStateException) { }
        }
    }
    @Test fun listeningRebindNeverCreatesTrackOrMergesDifferentHistories() {
        val old = setOf("old","app:a"); val new = setOf("new","app:a")
        assertNull(VisibilityReferencePolicy.listeningTrack(emptyMap(),old,new))
        assertEquals("track",VisibilityReferencePolicy.listeningTrack(mapOf("old" to "track"),old,new))
        assertEquals("track",VisibilityReferencePolicy.listeningTrack(mapOf("old" to "track","new" to "track"),old,new))
        for (conflict in listOf(mapOf("old" to "track","new" to "other"),mapOf("old" to "track","app:a" to "other"))) {
            try { VisibilityReferencePolicy.listeningTrack(conflict,old,new); fail("Track collision") }
            catch (_:IllegalStateException) { }
        }
    }
    @Test fun legacyVersionTwoJournalHasImplicitLogicalSource() {
        val old = record.copy(catalogSourceUri=null)
        val current = VisibilityJournalCodec.encode(old)
        // v3 adds one false byte after the optional origin for records with no alias.
        val payload = current.copyOfRange(0,current.size-33)
        java.nio.ByteBuffer.wrap(payload).putInt(4,2)
        val legacy = payload + java.security.MessageDigest.getInstance("SHA-256").digest(payload)
        assertEquals(old,VisibilityJournalCodec.decode(legacy))
        assertEquals(old.sourceUri,VisibilityJournalCodec.decode(legacy)!!.logicalSourceUri)
    }
}
