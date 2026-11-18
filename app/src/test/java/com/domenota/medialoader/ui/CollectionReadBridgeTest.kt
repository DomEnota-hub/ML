package com.domenota.medialoader.ui

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.MediaCollectionMemberEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.library.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class CollectionReadBridgeTest {
    private fun ref(id:String)=LibraryMediaRef(id,setOf("file-stat:$id","uri:content://media/external/audio/media/${id.hashCode()}"),
        DownloadEntity(id,"device","$id.mp3",MediaType.AUDIO,DownloadState.COMPLETED,
            "content://media/external/audio/media/${id.hashCode()}",100,100),null)
    private fun member(ref:LibraryMediaRef)=MediaCollectionMemberEntity("m-${ref.key}","c",MediaOrganizationPolicy.encode(MediaOrganizationPolicy.keys(ref)),null,1)
    private fun read(members:List<MediaCollectionMemberEntity>,refs:List<LibraryMediaRef>,blocked:Set<String> = emptySet())=
        materializedCollectionRead(members,refs,emptyMap(),blocked,emptyMap(),emptyMap())

    @Test fun knownFolderReadRequiresCurrentPublicAccessAndRejectsUnknownRecovery() {
        assertTrue(collectionReadAllowed(true,true,"folder",false))
        assertTrue(collectionReadAllowed(true,false,null,false))
        assertFalse(collectionReadAllowed(false,true,"folder",false))
        assertFalse(collectionReadAllowed(true,true,null,false))
        assertFalse(collectionReadAllowed(true,true,"folder",true))
    }
    @Test fun currentBlockedMemberIsSuppressedWhileUnaffectedCollectionMemberRemains() {
        val safe=ref("safe");val moving=ref("moving");val members=listOf(member(safe),member(moving))
        val value=read(members,listOf(safe,moving),setOf("file-stat:moving"))
        assertEquals(listOf(safe),value.refs)
        assertEquals(setOf(member(moving).id),value.suppressed)
        assertTrue(value.pending.isEmpty())
    }
    @Test fun recycledLocatorAndAmbiguousRequestedIdentityRemainPending() {
        val original=ref("safe");val m=member(original)
        val replaced=original.copy(aliases=original.aliases.filterNot { it.startsWith("file-stat:") }.toSet()+"file-stat:replacement")
        assertTrue(read(listOf(m),listOf(replaced)).resolved.isEmpty())
        val collision=original.copy(key="collision",item=original.item.copy(id="collision"))
        assertEquals(setOf(m.id),read(listOf(m),listOf(original,collision)).pending)
    }
    @Test fun retainedReadCannotReviveRemovedMemberChangedIdentityOrStaleUri() {
        val safe=ref("safe");val other=ref("other");val members=listOf(member(safe),member(other))
        val original=read(members,listOf(safe,other))
        fun retain(currentMembers:List<MediaCollectionMemberEntity>,refs:List<LibraryMediaRef>,blocked:Set<String> = emptySet())=
            retainMaterializedCollectionRead(original,currentMembers,refs.associateBy { it.key },emptyMap(),blocked,emptyMap(),emptyMap())!!
        assertEquals(listOf(safe),retain(listOf(member(safe)),listOf(safe,other)).refs)
        assertTrue(retain(listOf(member(safe).copy(identityJson=member(other).identityJson)),listOf(safe)).refs.isEmpty())
        assertTrue(retain(listOf(member(safe)),listOf(safe.copy(item=safe.item.copy(savedUri="file:///changed.mp3")))).refs.isEmpty())
        assertEquals(listOf(other),retain(members,listOf(other)).refs) // current kind/permission filter removed safe
        assertEquals(listOf(other),retain(members,listOf(safe,other),setOf("file-stat:safe")).refs)
    }
    @Test fun authenticatedFolderPreparationDoesNotBlankReadOnlyDetailsBeforeJournalExists() {
        assertTrue(collectionReadAllowed(true,true,null,false,preparingFolderOperation=true))
        assertFalse(collectionReadAllowed(true,true,null,false)) // Other historical recovery stays closed.
        assertFalse(collectionReadAllowed(true,true,null,true,preparingFolderOperation=true))
        assertFalse(collectionReadAllowed(false,true,null,false,preparingFolderOperation=true))
    }

}
