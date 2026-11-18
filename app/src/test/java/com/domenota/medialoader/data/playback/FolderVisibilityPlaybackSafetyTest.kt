package com.domenota.medialoader.data.playback

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.LibraryMediaRef
import com.domenota.medialoader.data.catalog.visibility.*
import com.domenota.medialoader.data.storage.HistoricalRecoverySignals
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FolderVisibilityPlaybackSafetyTest {
    private fun ref(id:String)=LibraryMediaRef(id,setOf(id,"file-stat:$id"),DownloadEntity(id,"device","$id.mp3",
        MediaType.AUDIO,DownloadState.COMPLETED,"file:///missing-$id.mp3",100,1000),null)
    private fun item(ref:LibraryMediaRef)=AudioPlaybackMetadata.item(ref.item,ref.aliases)
    private fun guard()=FolderVisibilityLibraryGuard(FolderVisibilityReadPolicy(FolderVisibilitySnapshot(),pendingFolder="folder"),emptyList(),emptyList())
    private fun access(refs:List<LibraryMediaRef>)=FolderAudioReadAccess(guard(),emptySet(),1,1,listOf(true),1,refs)
    private fun proof(items: List<MediaItem>) = runBlocking {
        FolderVisibilityPlaybackSafety.prove(items, { path ->
            setOf("file:$path", "file-stat:${path.substringAfter("/missing-").removeSuffix(".mp3")}")
        }, { emptySet() })
    }
    private fun cleanup(player: ExoPlayer, affected: Set<String>) {
        val items = FolderVisibilityPlaybackSafety.snapshot(player)
        FolderVisibilityPlaybackSafety.removeAffected(player, affected, items, proof(items), emptySet())
    }
    private fun denied(block:()->Unit){try{block();fail("Expected fail-closed authorization")}catch(_:IllegalStateException){}}

    @Test fun explicitScopedPermitPlaysUnaffectedTrackButDefaultGlobalMutationHoldRemains() {
        val safe=ref("safe");var live=access(listOf(safe))
        val permit=AudioQueueReadPermit.acquire(live,listOf(safe)){live}
        val player=ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        HistoricalRecoverySignals.beginFolderVisibility()
        try {
            denied { AudioQueuePlayback.startSelection(player,listOf(item(safe)),false) }
            assertTrue(AudioQueuePlayback.startSelection(player,listOf(item(safe)),false,permit=permit))
            assertEquals("safe",player.currentMediaItem?.mediaId)
            assertTrue(player.playWhenReady)
            AudioQueuePlayback.toggle(player,permit)
            assertFalse(player.playWhenReady)
        } finally { HistoricalRecoverySignals.end(changed=false);player.release() }
    }

    @Test fun movingUnknownPrivatePermissionAndIdentityChangesFailAgainAfterPreparation() {
        val safe=ref("safe");val original=access(listOf(safe));var live=original
        val permit=AudioQueueReadPermit.acquire(live,listOf(safe)){live}
        permit.requireCurrent(listOf(item(safe)))
        val move=VisibilityMoveRecord("op","folder",safe.key,VisibilityDirection.HIDE,safe.item.savedUri!!,
            safe.item.originalName,null,VisibilityFingerprint(100,"a".repeat(64)),"target")
        val pending=guard().copy(policy=FolderVisibilityReadPolicy(FolderVisibilitySnapshot(),pendingFolder="folder",pendingMembers=listOf(move)))
        val unknown=guard().copy(policy=FolderVisibilityReadPolicy(FolderVisibilitySnapshot(),unknownPending=true))
        for(changed in listOf(original.copy(visibility=pending),original.copy(visibility=unknown),
            original.copy(privacy=setOf("file-stat:safe")),original.copy(privacyEpoch=2),
            original.copy(musicEpoch=2),original.copy(permissionSignature=listOf(false)),
            original.copy(publicRefs=listOf(safe.copy(item=safe.item.copy(savedUri="file:///recycled.mp3")))))) {
            live=changed
            denied { permit.requireCurrent(listOf(item(safe))) }
        }
        live=original
        val stale=safe.copy(item=safe.item.copy(savedUri="file:///old.mp3"))
        denied { permit.requireCurrent(listOf(item(stale))) }
        val wrongPhysical=safe.copy(aliases=setOf("file-stat:recycled"))
        denied { AudioQueueReadPermit.acquire(original,listOf(wrongPhysical)){original} }
    }

    @Test fun targetedCleanupPreservesUnrelatedCurrentTrackPositionAndPlayState() {
        val a=ref("safe");val moving=ref("moving");val b=ref("other")
        val player=ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            player.setMediaItems(listOf(item(a),item(moving),item(b)))
            player.seekTo(0,1234);player.playWhenReady=true
            cleanup(player,setOf("file-stat:moving"))
            assertEquals(listOf("safe","other"),(0 until player.mediaItemCount).map{player.getMediaItemAt(it).mediaId})
            assertEquals("safe",player.currentMediaItem?.mediaId)
            assertEquals(1234L,player.currentPosition);assertTrue(player.playWhenReady)
        } finally {player.release()}
    }

    @Test fun affectedCurrentStopsAndUnknownQueueIdentityClearsFailClosed() {
        val a=ref("safe");val moving=ref("moving")
        val player=ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            player.setMediaItems(listOf(item(a),item(moving)))
            player.seekTo(1,1000);player.playWhenReady=true
            cleanup(player,setOf("moving"))
            assertFalse(player.playWhenReady);assertNotEquals("moving",player.currentMediaItem?.mediaId)
            player.setMediaItems(listOf(item(a),MediaItem.fromUri("file:///unknown.mp3")))
            cleanup(player,setOf("moving"))
            assertEquals(0,player.mediaItemCount);assertFalse(player.playWhenReady)
        } finally {player.release()}
    }
    @Test fun physicalReplacementHardLinkAndMissingStatCannotReuseAuthenticatedMetadata() = runBlocking {
        val items = listOf(item(ref("safe")))
        val replaced = FolderVisibilityPlaybackSafety.prove(items,
            { setOf("file:$it", "file-stat:moving") }, { error("Path proof must not open a provider") })
        assertNull(replaced.single())
        val missing = FolderVisibilityPlaybackSafety.prove(items,
            { setOf("file:$it") }, { error("Unknown path must fail closed") })
        assertNull(missing.single())
        val unchanged = FolderVisibilityPlaybackSafety.prove(items,
            { setOf("file:$it", "file-stat:safe") }, { error("No provider IO for authenticated paths") })
        assertTrue(unchanged.single()!!.contains("file-stat:safe"))
    }

    @Test fun sameIdQueueReplacementBetweenProofAndApplyClearsRatherThanRemovingStaleIndices() {
        val player=ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            player.setMediaItems(listOf(item(ref("safe")), item(ref("moving"))))
            val before=FolderVisibilityPlaybackSafety.snapshot(player)
            val verified=proof(before)
            // Retain ids/order but replace a physical locator; id-only checks are insufficient.
            player.replaceMediaItem(0, item(ref("safe").let { it.copy(item=it.item.copy(savedUri="file:///replaced.mp3")) }))
            FolderVisibilityPlaybackSafety.removeAffected(player,setOf("moving"),before,verified,emptySet())
            assertEquals(0,player.mediaItemCount)
            assertFalse(player.playWhenReady)
        } finally { player.release() }
    }

    @Test fun replacementProofUnknownPrivacyAndPrivacyChangeAfterSnapshotClearCurrentPlayback() {
        val player=ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            for (privacy in listOf<Set<String>?>(null, setOf("file-stat:safe"))) {
                player.setMediaItem(item(ref("safe")));player.playWhenReady=true
                val before=FolderVisibilityPlaybackSafety.snapshot(player)
                FolderVisibilityPlaybackSafety.removeAffected(player,setOf("moving"),before,proof(before),privacy)
                assertEquals(0,player.mediaItemCount);assertFalse(player.playWhenReady)
            }
            player.setMediaItem(item(ref("safe")));player.playWhenReady=true
            val before=FolderVisibilityPlaybackSafety.snapshot(player)
            FolderVisibilityPlaybackSafety.removeAffected(player,setOf("moving"),before,listOf(null),emptySet())
            assertEquals(0,player.mediaItemCount);assertFalse(player.playWhenReady)
        } finally { player.release() }
    }

    @Test fun revokedReadAndSelectedOnlyVideoCannotPreserveQueueEvenWithPhysicalProof() {
        val context=RuntimeEnvironment.getApplication()
        val app=org.robolectric.Shadows.shadowOf(context)
        app.grantPermissions(android.Manifest.permission.READ_MEDIA_AUDIO)
        assertEquals(emptySet<String>(), FolderVisibilityPlaybackSafety.currentPrivacyWithRead(context, MediaType.AUDIO, emptySet()))
        app.denyPermissions(android.Manifest.permission.READ_MEDIA_AUDIO, android.Manifest.permission.READ_MEDIA_VIDEO)
        app.grantPermissions(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertNull(FolderVisibilityPlaybackSafety.currentPrivacyWithRead(context, MediaType.AUDIO, emptySet()))
        assertNull(FolderVisibilityPlaybackSafety.currentPrivacyWithRead(context, MediaType.VIDEO, emptySet()))
        val player=ExoPlayer.Builder(context).build()
        try {
            player.setMediaItem(item(ref("safe")));player.playWhenReady=true
            val before=FolderVisibilityPlaybackSafety.snapshot(player)
            FolderVisibilityPlaybackSafety.removeAffected(player,emptySet(),before,proof(before),
                FolderVisibilityPlaybackSafety.currentPrivacyWithRead(context, MediaType.AUDIO, emptySet()))
            assertEquals(0,player.mediaItemCount);assertFalse(player.playWhenReady)
        } finally { player.release() }
    }

}
