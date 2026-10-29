package com.domenota.medialoader.data.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioQueuePlaybackTest {
    private fun item(id: String, private: Boolean = false) = MediaItem.Builder().setMediaId(id).setUri("file:///missing-$id.mp3")
        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putBoolean("private_media", private) }).build()).build()
    @Test fun previewOfCurrentTrackTogglesWithoutReplacingQueueOrPosition() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            player.setMediaItems(listOf(item("a"), item("b")))
            player.seekTo(1, 1234)
            player.pause()
            assertTrue(AudioQueuePlayback.preview(player, listOf(item("b"), item("a")), "b"))
            assertEquals(listOf("a", "b"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            assertEquals(1234L, player.currentPosition)
            assertTrue(player.playWhenReady)
            AudioQueuePlayback.preview(player, listOf(item("a")), "b")
            assertTrue(player.playWhenReady)
            AudioQueuePlayback.preview(player, listOf(item("b")), "b")
            assertFalse(player.playWhenReady)
        } finally { player.release() }
    }
    @Test fun differentTrackStartsAnOrderedPublicQueueAndRejectsPrivateSelection() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            assertTrue(AudioQueuePlayback.preview(player, listOf(item("phone"), item("private", true), item("download")), "download"))
            assertEquals(2, player.mediaItemCount)
            assertEquals("download", player.currentMediaItem?.mediaId)
            assertEquals(0L, player.currentPosition)
            assertFalse(AudioQueuePlayback.preview(player, listOf(item("private", true)), "private"))
            assertEquals("download", player.currentMediaItem?.mediaId)
        } finally { player.release() }
    }

    @Test fun samePhysicalTrackThroughOldPhoneAliasDoesNotRestart() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            player.setMediaItems(listOf(item("phone:audio:42")))
            player.seekTo(1234)
            player.pause()
            val metadata = MediaMetadata.Builder().setExtras(Bundle().apply {
                putStringArrayList(PublicMediaIdentity.EXTRA_KEYS, arrayListOf("phone:audio:42"))
            }).build()
            val download = item("download").buildUpon().setMediaMetadata(metadata).build()
            assertTrue(AudioQueuePlayback.preview(player, listOf(download), "download"))
            assertEquals("phone:audio:42", player.currentMediaItem?.mediaId)
            assertEquals(1234L, player.currentPosition)
            assertTrue(player.playWhenReady)
        } finally { player.release() }
    }

    @Test fun openingCurrentTrackKeepsPausePositionAndTheServiceQueue() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            player.setMediaItems(listOf(item("a"), item("b")))
            player.seekTo(1, 4567)
            player.pause()
            assertTrue(AudioQueuePlayback.open(player, listOf(item("b")), "b"))
            assertEquals(2, player.mediaItemCount)
            assertEquals(4567L, player.currentPosition)
            assertFalse(player.playWhenReady)
        } finally { player.release() }
    }

    @Test fun sharedPhysicalIdentityKeepsCurrentTrackEvenWhenRawAliasIsNoLongerListed() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        fun identified(id: String) = item(id).buildUpon().setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
            putStringArrayList(PublicMediaIdentity.EXTRA_KEYS, arrayListOf("media:external:42"))
        }).build()).build()
        try {
            player.setMediaItems(listOf(identified("phone:audio:42")))
            player.seekTo(4567)
            player.pause()
            assertTrue(AudioQueuePlayback.open(player, listOf(identified("download")), "download"))
            assertEquals("phone:audio:42", player.currentMediaItem?.mediaId)
            assertEquals(4567L, player.currentPosition)
            assertFalse(player.playWhenReady)
        } finally { player.release() }
    }

    @Test fun publicPlayerDoesNotExposePrivateSessionMetadata() {
        assertNull(AudioPlaybackMetadata.publicRow(item("secret", true), emptyList()))
        val public = AudioPlaybackMetadata.publicRow(item("public"), emptyList())!!
        assertEquals("public", public.id)
        assertFalse(public.isPrivate)
        assertEquals("file:///missing-public.mp3", public.savedUri)
    }
    @Test fun editingOtherEntriesKeepsCurrentTrackPositionAndPlayIntent() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            player.setMediaItems(listOf(item("a"), item("b"), item("c")))
            player.seekTo(1, 4321)
            player.playWhenReady = true
            assertTrue(AudioQueuePlayback.move(player, 2, 0))
            assertTrue(AudioQueuePlayback.remove(player, 1))
            assertEquals(listOf("c", "b"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            assertEquals("b", player.currentMediaItem?.mediaId)
            assertEquals(4321L, player.currentPosition)
            assertTrue(player.playWhenReady)
            assertFalse(AudioQueuePlayback.move(player, -1, 0))
            assertFalse(AudioQueuePlayback.remove(player, 10))
        } finally { player.release() }
    }
    @Test fun nextAndAppendDoNotRestartCurrentTrackOrDuplicateExistingEntry() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            player.setMediaItems(listOf(item("a"), item("b"), item("c")))
            player.seekTo(1, 4567)
            player.pause()
            assertTrue(AudioQueuePlayback.enqueue(player, item("d"), next = true))
            assertEquals(listOf("a", "b", "d", "c"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            assertTrue(AudioQueuePlayback.enqueue(player, item("a"), next = true))
            assertTrue(AudioQueuePlayback.enqueue(player, item("c"), next = false))
            assertEquals(listOf("b", "a", "d", "c"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            assertEquals("b", player.currentMediaItem?.mediaId)
            assertEquals(4567L, player.currentPosition)
            assertFalse(player.playWhenReady)
            assertFalse(AudioQueuePlayback.enqueue(player, item("secret", true), next = false))
            assertEquals(4, player.mediaItemCount)
        } finally { player.release() }
    }
    @Test fun removingCurrentEntryAdvancesWithoutPausingTheQueue() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            player.setMediaItems(listOf(item("a"), item("b"), item("c")))
            player.seekTo(1, 1234)
            player.playWhenReady = true
            assertTrue(AudioQueuePlayback.remove(player, 1))
            assertEquals("c", player.currentMediaItem?.mediaId)
            assertTrue(player.playWhenReady)
        } finally { player.release() }
    }

    @Test fun playPreparesAnIdleOrStoppedQueueWithoutResettingSelectedTrack() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            player.setMediaItems(listOf(item("a"), item("b")))
            player.seekTo(1, 1234)
            player.stop()
            assertEquals(androidx.media3.common.Player.STATE_IDLE, player.playbackState)
            AudioQueuePlayback.toggle(player)
            assertNotEquals(androidx.media3.common.Player.STATE_IDLE, player.playbackState)
            assertTrue(player.playWhenReady)
            assertEquals("b", player.currentMediaItem?.mediaId)
            assertEquals(1234L, player.currentPosition)
        } finally { player.release() }
    }
    @Test fun openingAnotherTrackSelectsItBeforeTheViewerSubscribesToSessionState() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            player.setMediaItems(listOf(item("a")))
            assertTrue(AudioQueuePlayback.open(player, listOf(item("a"), item("b")), "b"))
            assertEquals("b", player.currentMediaItem?.mediaId)
            assertTrue(player.playWhenReady)
            assertNotEquals(androidx.media3.common.Player.STATE_IDLE, player.playbackState)
        } finally { player.release() }
    }

}
