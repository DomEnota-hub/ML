package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class SelectiveArchivePlanBindingTest {
    private fun original(i:Int)=SelectiveArchiveIdentity(
        "download-$i","content://media/external/audio/media/$i",
        i.toLong(),"external_primary","Music/Shared/","track-$i.mp3",
        (100+i).toLong(),"a".repeat(64),1_700_000_000_000L+i,"b".repeat(64)
    )
    private val rows=(1..6).map(::original)
    private val checkpoint=SelectiveArchiveCheckpoint("virtual",rows,1_700_000_000_000L)
    private val signatures=rows.map { NomediaMediaSignature(it.displayName,it.sizeBytes) }
    private val ids=rows.map { it.catalogId }

    @Test fun acceptsSameSelectionInDifferentOrder() {
        assertTrue(selectiveArchivePlanMatches(checkpoint,"virtual",ids.reversed(),signatures.reversed()))
    }

    @Test fun membershipChangedEvenWhenNamesAndSizesIdenticalMustBlockReplay() {
        assertFalse(selectiveArchivePlanMatches(checkpoint,"virtual",
            ids.dropLast(1)+ "unrelated-download",signatures))
    }

    @Test fun oldCheckpointCannotApplyToAnotherFolderOrMissingMember() {
        assertFalse(selectiveArchivePlanMatches(checkpoint,"other",ids,signatures))
        assertFalse(selectiveArchivePlanMatches(checkpoint,"virtual",ids.dropLast(1),signatures))
        assertFalse(selectiveArchivePlanMatches(checkpoint,"virtual",
            ids.dropLast(1)+ids.first(),signatures))
    }

    @Test fun anyChangedPhysicalNameOrSizeBlocks() {
        assertFalse(selectiveArchivePlanMatches(checkpoint,"virtual",ids,
            signatures.mapIndexed { i,x -> if(i==3)x.copy(bytes=x.bytes+1) else x }))
        assertFalse(selectiveArchivePlanMatches(checkpoint,"virtual",ids,
            signatures.mapIndexed { i,x -> if(i==3)x.copy(name="unrelated.mp3") else x }))
    }

    @Test fun exactSixFrom806CannotSilentlyExpandTo806() {
        val physical=(1..806).map { NomediaMediaSignature("track-$it.mp3",(100+it).toLong()) }
        assertTrue(selectiveArchivePlanMatches(checkpoint,"virtual",ids,signatures))
        assertFalse(selectiveArchivePlanMatches(checkpoint,"virtual",ids,physical))
        assertEquals(ArchiveInventoryVerdict.RequiresSelectiveArchive(6,806),
            archiveInventoryVerdict(signatures,physical))
    }

    @Test fun invalidReplayedCheckpointCannotEvenBeConstructed() {
        assertThrows(IllegalArgumentException::class.java) {
            checkpoint.copy(originals=rows.mapIndexed { index,item ->
                if(index==1)item.copy(originalUri="file:///tmp/track.mp3") else item
            })
        }
    }
}
