package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AudioPlaybackModesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun shuffleButtonUsesPlayerOrderWithoutReplacingOrRestartingCurrentTrack() {
        lateinit var player: ExoPlayer
        var shuffle by mutableStateOf(false)
        compose.runOnUiThread {
            player = ExoPlayer.Builder(compose.activity).build()
            player.setMediaItems((0..3).map { MediaItem.Builder().setMediaId("song$it").setUri("file:///song$it.mp3").build() })
            player.setShuffleOrder(DefaultShuffleOrder(intArrayOf(2, 0, 3, 1), 0L))
            player.seekTo(0, 1234L)
            player.addListener(object : Player.Listener {
                override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffle = enabled }
            })
        }
        try {
            compose.setContent { MaterialTheme {
                AudioPlaybackModes(player, Player.REPEAT_MODE_OFF, shuffle)
            } }
            compose.onNodeWithContentDescription("Перемешать").assertIsOff().performClick().assertIsOn()
            compose.runOnUiThread {
                assertEquals(true, player.shuffleModeEnabled)
                assertEquals("song0", player.currentMediaItem?.mediaId)
                assertEquals(1234L, player.currentPosition)
                assertEquals(3, player.nextMediaItemIndex)
                player.seekToNextMediaItem()
                assertEquals("song3", player.currentMediaItem?.mediaId)
                player.seekToPreviousMediaItem()
                assertEquals("song0", player.currentMediaItem?.mediaId)
            }
            compose.onNodeWithContentDescription("Перемешать").performClick().assertIsOff()
            compose.runOnUiThread {
                assertEquals(false, player.shuffleModeEnabled)
                assertEquals(1, player.nextMediaItemIndex)
                assertEquals(listOf("song0", "song1", "song2", "song3"),
                    (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            }
        } finally { compose.runOnUiThread { player.release() } }
    }
}
