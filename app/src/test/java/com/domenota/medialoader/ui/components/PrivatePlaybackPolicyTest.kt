package com.domenota.medialoader.ui.components

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.domenota.medialoader.data.playback.PrivatePlaybackPolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivatePlaybackPolicyTest {
    private fun media(id: String, private: Boolean) = MediaItem.Builder().setMediaId(id)
        .setUri(if (private) "file:///data/user/0/app/files/private-media/$id.mp3" else "file:///sdcard/$id.mp3")
        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putBoolean("private_media", private) }).build()).build()

    @Test fun lockingRemovesPrivateTracksFromTheSession() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        try {
            player.setMediaItems(listOf(media("secret", true)))
            PrivatePlaybackPolicy.protect(player, false, setOf("secret"))
            assertEquals(0, player.mediaItemCount)
        } finally { player.release() }
    }

    @Test fun movingATrackToPrivateRemovesItsOldPublicQueueEntry() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        try {
            player.setMediaItems(listOf(media("public", false), media("moved", false)))
            PrivatePlaybackPolicy.protect(player, true, setOf("moved"))
            assertEquals(1, player.mediaItemCount)
            assertEquals("public", player.getMediaItemAt(0).mediaId)
        } finally { player.release() }
    }

    @Test fun unlockedPrivatePlaybackIsKept() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        try {
            player.setMediaItems(listOf(media("secret", true)))
            PrivatePlaybackPolicy.protect(player, true, setOf("secret"))
            assertEquals("secret", player.currentMediaItem?.mediaId)
        } finally { player.release() }
    }
}
