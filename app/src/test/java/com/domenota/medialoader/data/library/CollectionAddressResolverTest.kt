package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.MediaCollectionMemberEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.storage.SavedFileStatus
import com.domenota.medialoader.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class CollectionAddressResolverTest {
    private fun ref(n:Int, app:Boolean=true):LibraryMediaRef {
        val id="track-$n"; val uri="content://media/external/audio/media/$n"
        return LibraryMediaRef(id,setOf("file-stat:$n","uri:$uri"),
            DownloadEntity(id,"device","Song.$n.mp3",MediaType.AUDIO,DownloadState.COMPLETED,uri,100,100),id.takeIf{app})
    }
    private fun member(ref:LibraryMediaRef)=MediaCollectionMemberEntity("m-${ref.key}","c",MediaOrganizationPolicy.encode(MediaOrganizationPolicy.keys(ref)),null,1)
    @Test fun smallCollectionDoesNotWaitForTenThousandUnrelatedRows()=runBlocking {
        val all=(1..10000).map { ref(it) }; val selected=all.take(6); val calls=mutableListOf<String>()
        val result=CollectionAddressResolver.resolve(selected.map(::member),all.associate{it.key to it.item},null,false,emptySet(),
            { row -> calls+=row.id; if(row.id=="track-6") AddressedFile(null,SavedFileStatus.MISSING)
                else AddressedFile(all[row.id.substringAfterLast('-').toInt()-1],SavedFileStatus.PRESENT) },
            { error("No phone enumeration or unrelated URI lookup") })
        assertEquals(6,calls.size);assertEquals(5,result.resolved.size);assertTrue(result.pending.isEmpty())
        assertEquals(SavedFileStatus.MISSING,result.statuses["track-6"]?.second)
        assertEquals(selected.take(5).map{it.key},selected.take(5).map{result.resolved[member(it).id]?.key})
    }
    @Test fun directLocatorDoesNotAttachRecycledPhysicalFile()=runBlocking {
        val a=ref(1,false); val m=member(a); val recycled=a.copy(aliases=setOf("file-stat:other","uri:${a.item.savedUri}"))
        suspend fun resolve(candidate:LibraryMediaRef,complete:Boolean)=CollectionAddressResolver.resolve(listOf(m),emptyMap(),null,complete,emptySet(),
            {error("No app lookup")},{uri->assertEquals(a.item.savedUri,uri);AddressedFile(candidate,SavedFileStatus.PRESENT)})
        assertEquals(a.key,resolve(a,false).resolved[m.id]?.key)
        assertEquals(setOf(m.id),resolve(recycled,false).pending)
        assertTrue(resolve(recycled,true).resolved.isEmpty())
    }
    @Test fun missingLegacyIdentityWaitsForCompleteFallbackAndBlockedMembersNeverQuery()=runBlocking {
        val a=ref(1,false);val m=member(a).copy(identityJson=MediaOrganizationPolicy.encode(setOf("file-stat:1")))
        val pending=CollectionAddressResolver.resolve(listOf(m),emptyMap(),null,false,emptySet(),{error("Unexpected app")},{error("No locator")})
        assertEquals(setOf(m.id),pending.pending)
        val full=CollectionCatalogSnapshot(listOf(a),emptySet(),CollectionIdentityIndex.build(listOf(a)))
        val found=CollectionAddressResolver.resolve(listOf(m),emptyMap(),full,true,emptySet(),{error("Unexpected app")},{AddressedFile(a,SavedFileStatus.PRESENT)})
        assertEquals(a.key,found.resolved[m.id]?.key)
        val hidden=CollectionAddressResolver.resolve(listOf(m),emptyMap(),full,true,setOf("file-stat:1"),{error("Private app")},{error("Private phone")})
        assertEquals(setOf(m.id),hidden.suppressed);assertTrue(hidden.refs.isEmpty())
    }
    @Test fun metadataIsDisplayOnlyAndRequiresMatchingPublicIdentityAndReadAccess() {
        val a=ref(1,false);val m=member(a).copy(metadataJson=CollectionMemberMetadata.encode(a))
        assertEquals(a.item.originalName,CollectionMemberMetadata.display(m,emptySet()){true}?.originalName)
        assertNull(CollectionMemberMetadata.display(m,emptySet()){false})
        assertNull(CollectionMemberMetadata.display(m,setOf("file-stat:1")){true})
        assertNull(CollectionMemberMetadata.display(m.copy(identityJson=member(ref(2,false)).identityJson),emptySet()){true})
    }
    @Test fun routeProjectionRejectsCurrentMissingAndHiddenButNotStaleUriStatus() {
        val a=ref(1);val rows=mapOf(a.key to a.item);val missing=mapOf(a.key to SavedFileStatus.MISSING)
        assertEquals(listOf(a),filterRouteCatalog(listOf(a),emptySet(),rows,missing,mapOf(a.key to "old-uri")))
        assertTrue(filterRouteCatalog(listOf(a),emptySet(),rows,missing,mapOf(a.key to a.item.savedUri)).isEmpty())
        assertTrue(filterRouteCatalog(listOf(a),setOf("file-stat:1"),rows,emptyMap(),emptyMap()).isEmpty())
        assertTrue(filterRouteCatalog(listOf(a),emptySet(),mapOf(a.key to a.item.copy(isPrivate=true)),emptyMap(),emptyMap()).isEmpty())
    }
    @Test fun recentAudioKeepsLastCompleteGenerationButAccessChangesFailClosed() {
        val guard=AudioCatalogGuard(emptySet(),1);val refs=(1..5).map{ref(it)}
        val snapshot=retainAudioCatalog(null,AudioCatalogUpdate(guard,refs))!!
        repeat(4){assertSame(snapshot,retainAudioCatalog(snapshot,AudioCatalogUpdate(guard,null)))}
        assertNull(retainAudioCatalog(snapshot,AudioCatalogUpdate(guard.copy(accessEpoch=2),null)))
        assertNull(retainAudioCatalog(snapshot,AudioCatalogUpdate(guard.copy(privacy=setOf("track-1")),null)))
        assertNull(retainAudioCatalog(snapshot,AudioCatalogUpdate(null,null)))
        assertTrue(retainAudioCatalog(snapshot,AudioCatalogUpdate(guard,emptyList()))!!.refs.isEmpty())
    }
}
