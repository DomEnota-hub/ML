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
    @Test fun pendingRoutineRefreshKeepsPreviouslyConfirmedMembersButNotRealRemovalOrInvalidation()=runBlocking {
        val refs=(1..5).map { ref(it) }; val members=refs.map(::member); val history=refs.associate { it.key to it.item }
        suspend fun snapshot(count:Int, pending:Boolean=true)=CollectionAddressResolver.resolve(members,history,null,false,emptySet(),
            { row -> if(row.id.substringAfterLast('-').toInt()<=count) AddressedFile(refs.first{it.key==row.id},SavedFileStatus.PRESENT)
                else AddressedFile(null,if(pending)SavedFileStatus.UNCHECKED else SavedFileStatus.MISSING) },{error("No phone query")})
        val original=snapshot(5)
        for(count in listOf(1,5,4,5)){
            val refreshed=retainAddressedCollection(original,snapshot(count))
            assertEquals(5,refreshed.resolved.size)
        }
        assertEquals(4,retainAddressedCollection(original,snapshot(4,false)).resolved.size)
        val partial=snapshot(4)
        val lost=members.last().id
        assertEquals(4,retainAddressedCollection(original,partial.copy(invalidated=setOf(lost))).resolved.size)
        assertEquals(4,retainAddressedCollection(original,partial.copy(suppressed=setOf(lost))).resolved.size)
        assertEquals(4,retainAddressedCollection(original,partial.copy(checkedMembers=partial.checkedMembers+(lost to "different"))).resolved.size)
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
    @Test fun directPhoneMemberUsesVerifiedAppIdentityBeforeFullCatalog()=runBlocking {
        val phone=ref(1,false)
        val app=phone.copy(key="app-1",appDownloadId="app-1",item=phone.item.copy(id="app-1"))
        val m=member(phone)
        val found=CollectionAddressResolver.resolve(listOf(m),mapOf(app.key to app.item),null,false,emptySet(),
            { row -> assertEquals(app.key,row.id);AddressedFile(app,SavedFileStatus.PRESENT) },
            { AddressedFile(phone,SavedFileStatus.PRESENT) })
        assertEquals(app.key,found.resolved[m.id]?.key);assertEquals(1,found.refs.size)
    }
    @Test fun selectedOnlyPermissionCannotRevealMissingExternalMetadata() {
        val selected=setOf(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertTrue(PhoneMediaLibrary.canRead(MediaType.PHOTO,34,selected::contains))
        assertFalse(PhoneMediaLibrary.canReadMissingMetadata(MediaType.PHOTO,34,selected::contains))
        assertFalse(PhoneMediaLibrary.canReadMissingMetadata(MediaType.VIDEO,34,selected::contains))
        assertTrue(PhoneMediaLibrary.canReadMissingMetadata(MediaType.PHOTO,34){it==android.Manifest.permission.READ_MEDIA_IMAGES})
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

    @Test fun musicRouteDoesNotDemandGalleryEvenWhenGalleryNeverCompletes() {
        assertTrue(collectionScanModes(CollectionCatalogRequest.NONE).isEmpty())
        assertEquals(setOf(AppMode.MUSIC),collectionScanModes(CollectionCatalogRequest.AUDIO))
        assertEquals(setOf(AppMode.GALLERY,AppMode.MUSIC),collectionScanModes(CollectionCatalogRequest.ALL))
        val all=(1..11_190).map { ref(it) }
        val chosen=all.take(18)
        val snapshot=CollectionHistorySnapshot(all.associate { it.key to it.item },emptySet(),emptySet(),emptyList())
        val members=chosen.map(::member)
        val addressed=snapshot.forMembers(members)
        assertEquals(chosen.map { it.key }.toSet(),addressed.keys)
        val visual=CollectionMediaSignal(1,setOf("content://media/external/images/media/3"),setOf(AppMode.GALLERY))
        assertFalse(visual.affects(members,addressed))
        val otherAudio=CollectionMediaSignal(2,setOf("content://media/external/audio/media/100"),setOf(AppMode.MUSIC))
        assertFalse(otherAudio.affects(members,addressed))
        assertTrue(otherAudio.copy(uris=setOf(chosen.first().item.savedUri!!)).affects(members,addressed))
        assertFalse(visual.copy(uris=null).affects(members,addressed))
    }
    @Test fun cachedDisplayImmediatelyDropsChangedMembershipPrivacyAndConfirmedMissing()=runBlocking {
        val a=ref(1); val b=ref(2); val members=listOf(member(a),member(b));val history=listOf(a,b).associate { it.key to it.item }
        val value=CollectionAddressResolver.resolve(members,history,null,false,emptySet(),{ row -> AddressedFile(if(row.id==a.key)a else b,SavedFileStatus.PRESENT) },{error("no phone")})
        assertEquals(2,filterAddressedDisplay(value,members,history,emptySet(),emptyMap(),emptyMap()).resolved.size)
        assertEquals(1,filterAddressedDisplay(value,members.take(1),history,emptySet(),emptyMap(),emptyMap()).resolved.size)
        val blocked=filterAddressedDisplay(value,members,history,setOf(a.key),emptyMap(),emptyMap())
        assertFalse(member(a).id in blocked.resolved);assertTrue(member(a).id in blocked.suppressed)
        val missing=filterAddressedDisplay(value,members,history,emptySet(),mapOf(a.key to SavedFileStatus.MISSING),mapOf(a.key to a.item.savedUri))
        assertFalse(member(a).id in missing.resolved);assertFalse(member(a).id in missing.pending)
        val replaced=filterAddressedDisplay(value,members,history+(a.key to a.item.copy(savedUri="content://other")),emptySet(),emptyMap(),emptyMap())
        assertFalse(member(a).id in replaced.resolved);assertTrue(member(a).id in replaced.pending)
    }
    @Test fun oneSlowMemberDoesNotEraseEarlierConfirmedMember()=runBlocking {
        val a=ref(1);val b=ref(2);val members=listOf(member(a),member(b));val history=listOf(a,b).associate{it.key to it.item}
        val release=java.util.concurrent.CountDownLatch(1)
        try {
            val deadline=CollectionLookupDeadline(1_000,100)
            val result=CollectionAddressResolver.resolve(members,history,null,false,emptySet(),{ row -> deadline.lookup {
                if(row.id==a.key) AddressedFile(a,SavedFileStatus.PRESENT) else { release.await(10,java.util.concurrent.TimeUnit.SECONDS); AddressedFile(b,SavedFileStatus.PRESENT) }
            } },{error("no phone")})
            assertEquals(a.key,result.resolved[members.first().id]?.key)
            assertEquals(setOf(members.last().id),result.pending)
        } finally { release.countDown() }
    }
    @Test fun customCoverAccessChangesWithoutChangingCollectionMembership() {
        val source="content://media/external/images/media/42"
        val owned="content://media/external/images/media/43"
        val access=CollectionCoverAccess(true,setOf(owned))
        assertTrue(access.permits(source,emptySet()))
        val revoked=access.copy(fullPhotoAccess=false)
        assertFalse(revoked.permits(source,emptySet()))
        assertTrue(revoked.permits(owned,emptySet()))
        assertFalse(revoked.permits(owned,setOf("uri:$owned")))
        assertFalse(access.permits(source,null))
        assertFalse(revoked.copy(appPhotoUris=emptySet()).permits(owned,emptySet()))
    }
}
