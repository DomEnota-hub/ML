package com.domenota.medialoader.ui.screens.organization

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.LibraryMediaRef
import org.junit.Assert.*
import org.junit.Test

class CollectionCoverIdentityTest {
    private fun ref(uri: String = "content://media/external/images/media/11",
                    hidden: Boolean = false, privateFile: Boolean = false,
                    aliases: Set<String> = setOf("physical-hash-11")): LibraryMediaRef {
        val row = DownloadEntity("photo", "test", "photo.jpg", MediaType.PHOTO,
            DownloadState.COMPLETED, uri, 8192L, 1700000000000L,
            hidden = hidden, isPrivate = privateFile)
        return LibraryMediaRef("photo", aliases, row, null)
    }

    @Test fun stablePhysicalMemberRetainsAuthorizedCoverAcrossReferenceRebuild() {
        val old = ref()
        val new = old.copy(item = old.item.copy())
        val previous = mapOf("member-1" to CollectionCoverCandidate("same-identity", old))
        assertEquals(mapOf("member-1" to new),
            reusableCollectionCovers(previous, mapOf("member-1" to "same-identity"),
                listOf(new)))
    }

    @Test fun neverRetainsCoverForChangedMemberOrRetiredMediaStoreAddress() {
        val old = ref()
        val previous = mapOf("member-1" to CollectionCoverCandidate("identity-A", old))
        assertTrue(reusableCollectionCovers(previous,
            mapOf("member-1" to "identity-B"), listOf(old)).isEmpty())
        assertTrue(reusableCollectionCovers(previous,
            emptyMap(), listOf(old)).isEmpty())
        assertTrue(reusableCollectionCovers(previous,
            mapOf("member-1" to "identity-A"),
            listOf(ref(uri = "content://media/external/images/media/99"))).isEmpty())
        assertTrue(reusableCollectionCovers(previous,
            mapOf("member-1" to "identity-A"),
            listOf(ref(aliases = setOf("different-physical-identity")))).isEmpty())
    }

    @Test fun aPrivateHiddenOrRemovedCoverCannotSurviveRebind() {
        val old = ref()
        val previous = mapOf("member-1" to CollectionCoverCandidate("identity", old))
        for (unsafe in listOf(
            ref(hidden = true),
            ref(privateFile = true),
            old.copy(item = old.item.copy(state = DownloadState.FAILED))
        )) {
            assertTrue(reusableCollectionCovers(previous,
                mapOf("member-1" to "identity"), listOf(unsafe)).isEmpty())
        }
        assertTrue(reusableCollectionCovers(previous,
            mapOf("member-1" to "identity"), emptyList()).isEmpty())
    }
}
