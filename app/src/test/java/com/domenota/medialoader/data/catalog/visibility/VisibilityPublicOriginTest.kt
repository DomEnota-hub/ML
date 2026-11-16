package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class VisibilityPublicOriginTest {
    private val origin = VisibilityPublicOrigin("content://media/external/images/media/17", "external_primary",
        "Pictures/MediaLoader/", "Фото.jpg", "image/jpeg", "/storage/emulated/0/Pictures/MediaLoader/Фото.jpg",
        20L, 1700000000L, 1690000000L, 1680000000000L, "com.domenota.medialoader")
    @Test fun publicOriginSurvivesBothDirectionsAndAllPhases() {
        for (direction in VisibilityDirection.values()) for (phase in VisibilityMovePhase.values()) {
            val record = VisibilityMoveRecord("op", "folder", "id", direction,
                if (direction == VisibilityDirection.HIDE) origin.uri else "file:///hidden/Фото.jpg",
                origin.name, origin.modifiedMillis, VisibilityFingerprint(origin.bytes,"a".repeat(64)), "0123456789abcdef",
                if (phase == VisibilityMovePhase.PREPARED) null else "content://media/external/images/media/18", phase, origin)
            assertEquals(record,VisibilityJournalCodec.decode(VisibilityJournalCodec.encode(record)))
        }
    }
    @Test fun sourceMetadataMismatchCannotBeJournaled() {
        try { VisibilityMoveRecord("op","folder","id",VisibilityDirection.HIDE,origin.uri,"another.jpg",
            origin.modifiedMillis,VisibilityFingerprint(origin.bytes,"a".repeat(64)),"0123456789abcdef",publicOrigin=origin)
            fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun traversalAndVirtualVolumesCannotBeRestored() {
        for (path in listOf("/Pictures/", "Pictures/../", "Pictures/.hidden/", "Pictures//")) {
            try { origin.copy(relativePath=path); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        }
        try { origin.copy(volume="external"); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        assertEquals("ML-visibility-0123456789abcdef.jpg",origin.reservationName("0123456789abcdef"))
    }
    @Test fun legacyV1EmptyJournalStillDecodes() {
        val old = java.nio.ByteBuffer.allocate(9).putInt(0x4d4c4656).putInt(1).put(0.toByte()).array()
        assertNull(VisibilityJournalCodec.decode(old + java.security.MessageDigest.getInstance("SHA-256").digest(old)))
    }
}
