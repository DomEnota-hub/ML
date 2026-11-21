package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class SelectiveArchivePrivacyIndexTest {
    private fun member(id: Long, type: String = "audio") = SelectiveArchiveIdentity(
        catalogId="download-$id",
        originalUri="content://media/external_primary/$type/media/$id",
        mediaStoreId=id,
        volume="external_primary",
        relativePath="Music/Shared/",
        displayName="file-$id.mp3",
        sizeBytes=1000L,
        sha256="a".repeat(64),
        modifiedAtMillis=1700000000000L,
        referencesSha256="b".repeat(64),
    )

    @Test fun intentAndIncompleteRestorationBlockPublicAliasesBeforeAndAfterCrash() {
        for(phase in listOf(
            SelectiveArchiveMovePhase.HIDE_INTENT,
            SelectiveArchiveMovePhase.HIDDEN_VERIFIED,
            SelectiveArchiveMovePhase.RESTORE_INTENT,
            SelectiveArchiveMovePhase.NEEDS_REVIEW,
        )) {
            val state=archivePrivacyProjection(listOf(member(42) to phase))
            assertTrue(state.verified)
            assertEquals(setOf("download-42"),state.blockedIds)
            assertTrue("app:download-42" in state.blockedAliases)
            assertTrue("phone:audio:42" in state.blockedAliases)
            assertTrue("media:external:42" in state.blockedAliases)
            assertTrue("uri:content://media/external/audio/media/42" in state.blockedAliases)
            assertTrue("content://media/external_primary/audio/media/42" in state.blockedUris)
        }
    }

    @Test fun preparedAndVerifiedRestoredRecordsDoNotBypassNormalPublicGuard() {
        val state=archivePrivacyProjection(listOf(
            member(1) to null,
            member(2) to SelectiveArchiveMovePhase.PREPARED,
            member(3) to SelectiveArchiveMovePhase.RESTORED_VERIFIED,
            member(4) to SelectiveArchiveMovePhase.HIDDEN_VERIFIED,
        ))
        assertEquals(setOf("download-4"),state.blockedIds)
        assertFalse("download-3" in state.blockedAliases)
        assertFalse(selectiveArchiveBlocksPublic(null))
    }

    @Test fun duplicateIdsAndUrisNeverProduceVerifiedProjection() {
        assertThrows(IllegalArgumentException::class.java) {
            archivePrivacyProjection(listOf(member(1) to SelectiveArchiveMovePhase.HIDDEN_VERIFIED,
                member(1).copy(originalUri="content://media/external/audio/media/1") to
                    SelectiveArchiveMovePhase.NEEDS_REVIEW))
        }
    }

    @Test fun initialUnknownIsNotAConfirmedEmptyArchive() {
        assertFalse(SelectiveArchivePrivacySnapshot().verified)
        assertTrue(archivePrivacyProjection(emptyList()).verified)
    }
}
