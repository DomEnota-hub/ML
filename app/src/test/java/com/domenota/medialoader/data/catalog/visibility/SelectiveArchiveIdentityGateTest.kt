package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class SelectiveArchiveIdentityGateTest {
    private val hash = "a".repeat(64)
    private val links = "b".repeat(64)
    private fun identity(i: Int) = SelectiveArchiveIdentity(
        catalogId="download-$i",
        originalUri="content://media/external/audio/media/$i",
        mediaStoreId=i.toLong(),
        volume="external_primary",
        relativePath="Music/Shared/",
        displayName="track-$i.mp3",
        sizeBytes=1000L+i,
        sha256=hash,
        modifiedAtMillis=1_700_000_000_000L+i,
        referencesSha256=links,
    )
    private fun signature(i:Int)=NomediaMediaSignature("track-$i.mp3",1000L+i)

    @Test fun sixFrom806RequiresSeparateSelectiveEngineAndNeverAuthorizesParentMarker() {
        val selected=(1..6).map(::identity)
        val physical=(1..806).map(::signature)
        assertEquals(null,verifySelectiveArchiveSources(selected,(1..6).map(::signature),physical))
        assertEquals(ArchiveInventoryVerdict.RequiresSelectiveArchive(6,806),
            archiveInventoryVerdict((1..6).map(::signature),physical))
        assertFalse(nomediaInventoryMatches((1..6).map(::signature),physical))
    }

    @Test fun changedIdentityAndDuplicateMediaStoreRowsRejectPreflight() {
        val original=(1..6).map(::identity)
        val physical=(1..806).map(::signature)
        assertEquals(SelectiveArchiveBarrier.UNKNOWN_OR_CHANGED_SOURCE,
            verifySelectiveArchiveSources(original.mapIndexed{n,x->
                if(n==5)x.copy(mediaStoreId=1L,volume=original[0].volume) else x
            },(1..6).map(::signature),physical))
        assertEquals(SelectiveArchiveBarrier.UNKNOWN_OR_CHANGED_SOURCE,
            verifySelectiveArchiveSources(original.mapIndexed{n,x->
                if(n==0)x.copy(displayName="foreign.mp3") else x
            },(1..6).map(::signature),physical))
        assertEquals(SelectiveArchiveBarrier.UNKNOWN_OR_CHANGED_SOURCE,
            verifySelectiveArchiveSources(original.mapIndexed{n,x->
                if(n==0)x.copy(sha256="not-a-hash") else x
            },(1..6).map(::signature),physical))
        assertEquals(SelectiveArchiveBarrier.IDENTITY_COLLISION,
            verifySelectiveArchiveSources(original,(1..6).map(::signature),(1..6).map(::signature)))
    }

    @Test fun completeRestoreRequiresSameIdBytesPathDateLinksAndActualGalleryVisibility() {
        val before=identity(42)
        assertNull(verifySelectiveArchiveRestoration(before,before,true,true))
        val bad=listOf(
            before.copy(originalUri="content://media/external/audio/media/43",mediaStoreId=43),
            before.copy(mediaStoreId=43),
            before.copy(relativePath="Music/Restored/"),
            before.copy(displayName="new.mp3"),
            before.copy(sizeBytes=before.sizeBytes+1),
            before.copy(sha256="c".repeat(64)),
            before.copy(modifiedAtMillis=before.modifiedAtMillis+1),
        )
        bad.forEach {
            assertEquals(SelectiveArchiveBarrier.UNVERIFIED_RESTORATION,
                verifySelectiveArchiveRestoration(before,it,true,true))
        }
        assertEquals(SelectiveArchiveBarrier.REFERENCES_CHANGED,
            verifySelectiveArchiveRestoration(before,before.copy(referencesSha256="c".repeat(64)),true,true))
        assertEquals(SelectiveArchiveBarrier.REFERENCES_CHANGED,
            verifySelectiveArchiveRestoration(before,before,true,null))
        assertEquals(SelectiveArchiveBarrier.SYSTEM_VISIBILITY_NOT_CONFIRMED,
            verifySelectiveArchiveRestoration(before,before,null,true))
        assertEquals(SelectiveArchiveBarrier.SYSTEM_VISIBILITY_NOT_CONFIRMED,
            verifySelectiveArchiveRestoration(before,before,false,true))
        assertEquals(SelectiveArchiveBarrier.UNVERIFIED_RESTORATION,
            verifySelectiveArchiveRestoration(before,null,true,true))
    }

    @Test fun unknownAndUnsafePathsNeverLookLikeProvenOrigins() {
        val baseline=identity(1)
        assertFalse(baseline.copy(originalUri="file:///storage/track.mp3").wellFormed())
        assertFalse(baseline.copy(relativePath="../Music/").wellFormed())
        assertFalse(baseline.copy(relativePath="/Music/").wellFormed())
        assertFalse(baseline.copy(relativePath="Music//").wellFormed())
        assertFalse(baseline.copy(displayName="../track.mp3").wellFormed())
        assertFalse(baseline.copy(referencesSha256="").wellFormed())
        assertFalse(baseline.copy(modifiedAtMillis=-1).wellFormed())
    }
}
