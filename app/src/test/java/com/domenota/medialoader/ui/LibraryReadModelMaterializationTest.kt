package com.domenota.medialoader.ui

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.AppMode
import com.domenota.medialoader.data.library.LibraryMediaRef
import com.domenota.medialoader.data.catalog.visibility.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class LibraryReadModelMaterializationTest {
    private fun request(revision: Int, recovering: Boolean = false, known: Boolean = false,
        access: Any? = "permission-private-1", valid: Boolean = true) =
        LibraryMaterializationRequest(revision, access, recovering, known, valid)

    @Test fun fourFileHideAndShowBuildOnceAtEachExitAndNeverForIntermediateReceipts() = runBlocking {
        val requests = mutableListOf(request(0))
        for (batch in 0..1) {
            for (file in 0..4) requests += request(batch * 10 + file + 1, recovering=true, known=true)
            // Manager may drop pendingFolder before HistoricalRecoverySignals exits.
            requests += request(batch * 10 + 6,recovering=true)
            requests += request(batch * 10 + 7)
        }
        val builds = mutableListOf<Int>()
        val initialPublished = CompletableDeferred<Unit>()
        val snapshots = requests.asFlow().onEach { if (it.input != 0) initialPublished.await() }
            .materializeLibraryReadModel(-1) { revision -> builds += revision; revision }
            .onEach { if (it == 0) initialPublished.complete(Unit) }.toList()
        assertEquals(listOf(0,7,17),builds)
        assertTrue(snapshots.all { it in setOf(-1,0,7,17) })
        assertEquals(17,snapshots.last())
    }

    @Test fun unknownPrivacyRevokeAndSelectedOnlyNeverRetainAnEarlierAccessGeneration() = runBlocking {
        for (invalid in listOf(
            request(3,recovering=true,known=true,access="private-2"),
            request(3,recovering=true,known=true,access="permission-revoked"),
            request(3,recovering=true,known=true,access="selected-only-2"),
            request(3,recovering=true,known=false,valid=false),
            request(3,recovering=true,known=true,access=null),
        )) {
            val builds=mutableListOf<Int>()
            val published=CompletableDeferred<Unit>()
            val invalidPublished=CompletableDeferred<Unit>()
            var invalidSent=false
            val snapshots=flow {
                emit(request(0)); published.await()
                emit(request(1,true,true))
                invalidSent=true
                emit(invalid); invalidPublished.await()
                emit(invalid.copy(input=4))
                emit(invalid.copy(input=5,recovering=false,knownFolder=false))
            }.materializeLibraryReadModel(-1) { builds += it;it }.onEach {
                if(it==0) published.complete(Unit)
                if(invalidSent && it == -1) invalidPublished.complete(Unit)
            }.toList()
            assertTrue(invalidPublished.isCompleted)
            assertFalse(3 in builds);assertFalse(4 in builds)
            assertEquals(if(invalid.valid && invalid.access!=null) 5 else -1,snapshots.last())
        }
    }

    @Test fun unrelatedHistoricalRecoveryWithoutKnownFolderDoesNotSuppressIndexing() = runBlocking {
        val builds=mutableListOf<Int>()
        val snapshots=listOf(request(0),request(1,recovering=true),request(2,recovering=true),request(3))
            .asFlow().materializeLibraryReadModel(-1) { builds += it;it }.toList()
        assertEquals(listOf(0,1,2,3),builds)
        assertEquals(3,snapshots.last())
    }
    @Test fun mixedRawIndexCoalescesAudioRevokeButCurrentPublicReadersExcludeOnlyRevokedKind() = runBlocking {
        fun ref(id: String, type: MediaType) = LibraryMediaRef(id,emptySet(),DownloadEntity(id,"device",id,type,
            DownloadState.COMPLETED,"content://media/$id",100,1000),null)
        val photo=ref("photo",MediaType.PHOTO);val audio=ref("song",MediaType.AUDIO)
        val guard=FolderVisibilityLibraryGuard(FolderVisibilityReadPolicy(FolderVisibilitySnapshot()),emptyList(),emptyList())
        val current=PhoneLibraryState(loaded=true,complete=true,revision=4,requiresValidation=false,accessEpoch=1)
        val revoked=current.copy(loaded=false,revision=5,requiresValidation=true,accessEpoch=2)
        val privacy=emptySet<String>();val builds=mutableListOf<Int>()
        val requests=listOf(request(0,access=privacy),request(1,true,true,privacy),
            request(2,true,true,privacy),request(3,true,true,setOf("new-private")))
        val initialPublished=CompletableDeferred<Unit>()
        val retainedPublished=CompletableDeferred<Unit>()
        val snapshots=flow {
            emit(requests[0]);initialPublished.await()
            emit(requests[1]);emit(requests[2]);retainedPublished.await()
            emit(requests[3])
        }.materializeLibraryReadModel(LibraryIndex()) { revision ->
            builds += revision
            LibraryIndex(ready=true,refs=listOf(photo,audio),galleryRevision=4,musicRevision=4,
                privacyKeys=privacy,visibility=guard,galleryAccessEpoch=1,musicAccessEpoch=1)
        }.onEach {
            if(it.ready) {
                if(initialPublished.isCompleted) retainedPublished.complete(Unit)
                else initialPublished.complete(Unit)
            }
        }.toList()
        assertEquals(listOf(0),builds)
        val retained=snapshots.last { it.ready }
        assertEquals(listOf(photo,audio),retained.refs) // Raw data itself grants no access.
        assertEquals(listOf(photo),guardedRetainedIndexRefs(retained,guard,privacy,current,revoked,true))
        assertFalse(visibleLibraryValidationPending(AppMode.GALLERY,retained,current,revoked))
        assertTrue(visibleLibraryValidationPending(AppMode.MUSIC,retained,current,revoked))
        assertFalse(snapshots.last().ready);assertTrue(snapshots.last().refs.isEmpty())
    }

    @Test fun firstSubscriptionDuringKnownBatchPublishesColdMilestonesWithoutReceiptBuildStorm() = runBlocking {
        val requests=listOf(
            request(0,true,true), // No authenticated source exists yet.
            request(1,true,true).copy(bootstrapKey="app-first"),
            request(2,true,true).copy(bootstrapKey="app-first"),
            request(3,true,true).copy(bootstrapKey="app-full-gallery-first-e1"),
            request(4,true,true).copy(bootstrapKey="app-full-gallery-first-e1"),
            request(5,true,true).copy(bootstrapKey="app-full-gallery-full-e1"),
            request(6,true,true).copy(bootstrapKey="app-full-gallery-full-music-first-e1"),
            request(7,true,true).copy(bootstrapKey="app-full-gallery-full-music-full-e1"),
            request(8,true,true).copy(bootstrapKey="app-full-gallery-full-music-full-e1"),
            request(9).copy(bootstrapKey="app-full-gallery-full-music-full-e1"))
        val builds=mutableListOf<Int>()
        val releaseFirst=CompletableDeferred<Unit>()
        val published=listOf(1,3,5,6,7).associateWith { CompletableDeferred<Unit>() }
        val snapshots=flow {
            emit(requests[0]);emit(requests[1]);emit(requests[2])
            // The same-key receipt has passed selection while build 1 is suspended.
            releaseFirst.complete(Unit);published.getValue(1).await()
            emit(requests[3]);published.getValue(3).await();emit(requests[4])
            emit(requests[5]);published.getValue(5).await()
            emit(requests[6]);published.getValue(6).await()
            emit(requests[7]);published.getValue(7).await();emit(requests[8]);emit(requests[9])
        }.materializeLibraryReadModel(-1) {
            builds += it
            if(it==1) releaseFirst.await()
            it
        }.onEach { published[it]?.complete(Unit) }.toList()
        assertEquals(listOf(1,3,5,6,7,9),builds)
        assertTrue(listOf(1,3,5,6,7,9).all(snapshots::contains))
        assertEquals(9,snapshots.last())
    }

    @Test fun cancelledColdBuildCannotMarkItsMilestonePublishedAndSuppressNextAttempt() = runBlocking {
        val builds=mutableListOf<Int>()
        val requests=listOf(request(1,true,true),request(2,true,true))
            .map { it.copy(bootstrapKey="same-first-page") }
        val snapshots=requests.asFlow().materializeLibraryReadModel(-1) { revision ->
            builds += revision
            // Simulate cancellation after building, before mapLatest can deliver it.
            if(revision==1) currentCoroutineContext().cancel()
            revision
        }.toList()
        assertEquals(listOf(1,2),builds)
        assertFalse(1 in snapshots)
        assertEquals(2,snapshots.last())
    }

}
