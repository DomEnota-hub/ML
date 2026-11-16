package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.MediaCollectionMemberEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class CollectionCatalogSnapshotTest {
    private fun ref(n:Int, app:Boolean=true):LibraryMediaRef {
        val id="track-$n"; val uri="content://media/external/audio/media/$n"
        val row=DownloadEntity(id,"device","Track.$n.mp3",MediaType.AUDIO,DownloadState.COMPLETED,uri,100,100)
        return LibraryMediaRef(id,setOf("file-stat:$n","uri:$uri"),row,id.takeIf{app})
    }
    private fun member(ref:LibraryMediaRef)=MediaCollectionMemberEntity("m-${ref.key}","c",MediaOrganizationPolicy.encode(MediaOrganizationPolicy.keys(ref)),null,1)
    @Test fun partialOneFiveFourFiveNeverReplacesCompleteCollection() {
        val refs=(1..5).map{ref(it)}; val members=refs.map(::member)
        var last=CollectionCatalogSnapshot.select(null,refs,true,true,emptySet())!!
        val original=last
        for(n in listOf(1,5,4,5)) {
            last=CollectionCatalogSnapshot.select(last,refs.take(n),false,true,emptySet())!!
            assertSame(original,last)
            assertEquals(refs.map{it.key},members.mapNotNull{last.identityIndex.resolve(listOf(it),last.refs.associateBy{r->r.key})[it.id]?.key})
        }
        val afterRemoval=CollectionCatalogSnapshot.select(last,refs.take(4),true,true,emptySet())!!
        assertEquals(4,afterRemoval.identityIndex.resolve(members,afterRemoval.refs.associateBy{it.key}).size)
        val added=refs+ref(6)
        val afterAdd=CollectionCatalogSnapshot.select(afterRemoval,added,true,true,emptySet())!!
        assertEquals(6,afterAdd.identityIndex.resolve(added.map(::member),added.associateBy{it.key}).size)
    }
    @Test fun firstPartialAndPrivacyOrPermissionChangeFailClosed() {
        val refs=(1..5).map{ref(it)}
        assertNull(CollectionCatalogSnapshot.select(null,refs.take(1),false,true,emptySet()))
        val prior=CollectionCatalogSnapshot.select(null,refs,true,true,emptySet())!!
        assertNull(CollectionCatalogSnapshot.select(prior,refs,false,true,setOf(refs.first().key)))
        assertNull(CollectionCatalogSnapshot.select(prior,refs,true,false,emptySet()))
        assertNull(CollectionCatalogSnapshot.select(prior,refs,true,true,null))
        assertNull(CollectionCatalogSnapshot.select(null,refs,false,true,emptySet()))
    }
    @Test fun sameGenerationResolutionRejectsAmbiguousAndStaleCanonicalUris() {
        val a=ref(1); val m=member(a)
        val index=CollectionIdentityIndex.build(listOf(a))
        assertEquals(a,index.resolve(listOf(m),mapOf(a.key to a))[m.id])
        val changed=a.copy(item=a.item.copy(savedUri="content://media/new"))
        assertTrue(index.resolve(listOf(m),mapOf(a.key to changed)).isEmpty())
        val duplicate=a.copy(key="other",appDownloadId="other")
        assertTrue(CollectionIdentityIndex.build(listOf(a,duplicate)).resolve(listOf(m),mapOf(a.key to a,duplicate.key to duplicate)).isEmpty())
        assertTrue(index.resolve(listOf(m),emptyMap()).isEmpty())
    }
    @Test fun blockedMembersAndHistoryCannotExposePrivateOrHiddenMetadata() {
        val a=ref(1); val member=member(a)
        assertEquals(a.item,collectionMissingHistory(member,mapOf(a.key to a.item),emptySet()))
        assertTrue(collectionMemberBlocked(member,setOf(a.key)))
        assertTrue(collectionMemberBlocked(member,setOf("uri:${a.item.savedUri}")))
        assertTrue(collectionRefBlocked(a,setOf("file-stat:1")))
        assertNull(collectionMissingHistory(member,mapOf(a.key to a.item),setOf(a.key)))
        assertNull(collectionMissingHistory(member,mapOf(a.key to a.item.copy(isPrivate=true)),emptySet()))
        assertNull(collectionMissingHistory(member,mapOf(a.key to a.item.copy(hidden=true)),emptySet()))
        val phone=ref(2,false)
        assertNull(collectionMissingHistory(member(phone),mapOf(phone.key to phone.item),emptySet()))
        assertFalse(collectionCoverAllowed(a.item.savedUri!!,setOf("uri:${a.item.savedUri}")))
        assertFalse(collectionCoverAllowed(a.item.savedUri!!,null))
    }
    @Test fun automaticCoverNeverChangesToAudioDuringPartialOrReorderedDelivery() {
        val video=ref(1).copy(item=ref(1).item.copy(mediaType=MediaType.VIDEO))
        val rows=listOf(video)+(2..18).map { n ->
            ref(n).let{it.copy(item=it.item.copy(mediaType=if(n<=6)MediaType.AUDIO else MediaType.PHOTO))}
        }
        assertEquals(5,rows.count{it.item.mediaType==MediaType.AUDIO})
        val members=rows.mapIndexed { i,r -> member(r).copy(sortOrder=i.toLong()) }
        val resolved=members.zip(rows).associate{it.first.id to it.second}
        // The persisted first member is authoritative even if another media section resolves first.
        assertEquals(video,collectionAutomaticCover(members,resolved,emptySet()))
        assertNull(collectionAutomaticCover(members,resolved.filterValues{it!=video},emptySet()))
        assertNull(collectionAutomaticCover(members,emptyMap(),emptySet(),rows[1]))
        assertEquals(video,collectionAutomaticCover(members.reversed(),resolved,emptySet(),rows[1]))
        assertEquals(video,collectionAutomaticCover(members.map{it.copy()},emptyMap(),emptySet(),video))
        val reordered=members.mapIndexed{i,m->m.copy(sortOrder=if(i==1)-1 else i.toLong())}
        assertEquals(rows[1],collectionAutomaticCover(reordered,resolved,emptySet(),video))
    }
    @Test fun automaticCoverRejectsOldBindingAndUnavailablePrivacy() {
        val a=ref(1); val b=ref(2)
        val member=member(a)
        val rebound=member.copy(identityJson=member(b).identityJson)
        assertNull(collectionAutomaticCover(listOf(rebound),mapOf(member.id to a),emptySet(),a))
        assertNull(collectionAutomaticCover(listOf(member),mapOf(member.id to a),null,a))
        assertNull(collectionAutomaticCover(listOf(member),mapOf(member.id to a),setOf(a.key),a))
        assertNull(collectionAutomaticCover(listOf(member),mapOf(member.id to a.copy(item=a.item.copy(hidden=true))),emptySet()))
        val tied=listOf(member.copy(id="b"),member(b).copy(id="a"))
        assertEquals("a",collectionAutomaticCoverMember(tied,emptySet())?.id)
        assertEquals("a",collectionAutomaticCoverMember(tied.reversed(),emptySet())?.id)
    }

}
