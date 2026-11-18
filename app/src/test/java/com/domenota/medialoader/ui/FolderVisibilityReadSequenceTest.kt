package com.domenota.medialoader.ui

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.data.catalog.visibility.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses real Android URI normalization; keep unrelated pure policy tests on plain JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FolderVisibilityReadSequenceTest {
    private fun publicPhoto(id: String, uri: String): LibraryMediaRef =
        LibraryMediaRef(id, setOf(id, uri), DownloadEntity(id, "test", "$id.jpg",
            MediaType.PHOTO, DownloadState.COMPLETED, uri, 200L, 1700000000000L), id)
    @Test fun fourFileHideShowNeverRemovesUntouchedPhotoOrMusicAndNeverRevivesRetiredUri() {
        val moving=(1..4).map { n -> publicPhoto("move$n","content://media/external/images/media/$n") }
        val safe=publicPhoto("safe","content://media/external/images/media/10")
        val song=publicPhoto("song","content://media/external/audio/media/11").let { it.copy(item=it.item.copy(mediaType=MediaType.AUDIO)) }
        val old=FolderVisibilityLibraryGuard(FolderVisibilityReadPolicy(FolderVisibilitySnapshot()),emptyList(),emptyList())
        val index=LibraryIndex(ready=true,refs=moving+listOf(safe,song),galleryRevision=8,musicRevision=5,privacyKeys=emptySet(),visibility=old)
        val photos=PhoneLibraryState(loaded=true,complete=true,requiresValidation=false,revision=8)
        val music=PhoneLibraryState(loaded=true,complete=true,requiresValidation=false,revision=5)
        val moves=moving.map { ref -> VisibilityMoveRecord("batch","folder",ref.key,VisibilityDirection.HIDE,
            ref.item.savedUri!!,ref.item.originalName,null,VisibilityFingerprint(200,"a".repeat(64)),"target-${ref.key}") }
        val receipts=moves.map { it.copy(phase=VisibilityMovePhase.CLEANED,destinationUri="content://private/${it.downloadId}") }
        for(n in 0..4) {
            val hide=old.copy(policy=FolderVisibilityReadPolicy(FolderVisibilitySnapshot(receipts=receipts.take(n)),
                pendingFolder="folder",pendingMembers=moves.drop(n)))
            assertEquals(listOf(safe,song),guardedRetainedIndexRefs(index,hide,emptySet(),photos,music,true))
            assertFalse(visibleLibraryValidationPending(AppMode.GALLERY,index,photos,music))
            assertFalse(visibleLibraryValidationPending(AppMode.MUSIC,index,photos,music))
        }
        val show=receipts.map { it.copy(direction=VisibilityDirection.SHOW,sourceUri=it.destinationUri!!,
            destinationUri="content://media/external/images/media/new-${it.downloadId}") }
        for(n in 0..4) {
            val snapshot=FolderVisibilitySnapshot(receipts=show.take(n)+receipts.drop(n),retiredLocators=moving.mapTo(mutableSetOf()){it.item.savedUri!!})
            val guard=old.copy(policy=FolderVisibilityReadPolicy(snapshot,pendingFolder=if(n<4) "folder" else null,
                pendingMembers=show.drop(n)))
            assertEquals(listOf(safe,song),guardedRetainedIndexRefs(index,guard,emptySet(),photos,music,true))
        }
    }

}
