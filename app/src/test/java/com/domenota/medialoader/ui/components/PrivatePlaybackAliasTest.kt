package com.domenota.medialoader.ui.components

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.domenota.medialoader.data.playback.PrivatePlaybackPolicy
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivatePlaybackAliasTest {
    @Test fun phoneAudioAliasOfDownloadsEntryIsRemovedRegardlessOfUnlock() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        for (unlocked in listOf(false, true)) {
            val player = ExoPlayer.Builder(app).build()
            try {
                val phone = MediaItem.Builder().setMediaId("phone:audio:42")
                    .setUri("content://media/external/audio/media/42").build()
                val unrelated = MediaItem.Builder().setMediaId("phone:audio:43")
                    .setUri("content://media/external/audio/media/43").build()
                player.setMediaItems(listOf(phone, unrelated))
                val blocked = setOf("app-download-id") + PublicMediaIdentity.uriKeys("content://media/external_primary/downloads/42")
                PrivatePlaybackPolicy.protect(player, unlocked, blocked)
                assertEquals(1, player.mediaItemCount)
                assertEquals("phone:audio:43", player.currentMediaItem?.mediaId)
            } finally { player.release() }
        }
    }

    @Test fun mediaDocumentAndCollectionUrisShareIdentityWithoutCrossingVolumes() {
        val document = PublicMediaIdentity.uriKeys("content://com.android.providers.media.documents/document/audio%3A42")
        val collection = PublicMediaIdentity.uriKeys("content://media/external_primary/audio/media/42")
        assertTrue(document.intersect(collection).isNotEmpty())
        assertTrue(collection.intersect(PublicMediaIdentity.uriKeys("content://media/1234-5678/audio/media/42")).isEmpty())
    }

    @Test fun unlockedPrivateEntrySurvivesEvenWhenItsIdentityIsBlockedButLocksRemoveIt() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        try {
            val item = MediaItem.Builder().setMediaId("app-download-id")
                .setUri("file:///data/user/0/app/files/private-media/secret.mp3")
                .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
                    putBoolean("private_media", true)
                    putStringArrayList(PublicMediaIdentity.EXTRA_KEYS, arrayListOf("file-stat:identity"))
                }).build()).build()
            player.setMediaItem(item)
            PrivatePlaybackPolicy.protect(player, true, setOf("app-download-id", "file-stat:identity"))
            assertEquals(1, player.mediaItemCount)
            PrivatePlaybackPolicy.protect(player, false, setOf("app-download-id", "file-stat:identity"))
            assertEquals(0, player.mediaItemCount)
        } finally { player.release() }
    }

    @Test fun opaqueSafAliasCanBeMatchedUsingCapturedIdentity() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        try {
            val item = MediaItem.Builder().setMediaId("different-app-id")
                .setUri("content://documents/opaque/7")
                .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
                    putStringArrayList(PublicMediaIdentity.EXTRA_KEYS, arrayListOf("file-stat:identity"))
                }).build()).build()
            player.setMediaItem(item)
            PrivatePlaybackPolicy.protect(player, true, setOf("file-stat:identity"))
            assertEquals(0, player.mediaItemCount)
        } finally { player.release() }
    }

    @Test fun fileIdentityNeverReadsAudioBytes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.cacheDir, "identity-test.mp3").apply { writeText("unchanged") }
        try {
            val keys = PublicMediaIdentity.capture(context, android.net.Uri.fromFile(file).toString())
            assertTrue(keys.containsAll(PublicMediaIdentity.uriKeys(android.net.Uri.fromFile(file).toString())))
            assertEquals("unchanged", file.readText())
        } finally { file.delete() }
    }
}
