package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.ui.screens.music.MusicPlayerControls
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicPlayerControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun playerActionsReachSessionAndSecondaryControlsRemainReachable() {
        val player = ExoPlayer.Builder(compose.activity).build()
        val track = DownloadEntity("a", "device", "Track", MediaType.AUDIO, DownloadState.COMPLETED, "content://media/42", null, 0)
        var eq = 0; var queue = 0; var favorite = 0
        try {
            player.setMediaItems(listOf(MediaItem.Builder().setMediaId("a").setUri("file:///a.mp3").build(),
                MediaItem.Builder().setMediaId("b").setUri("file:///b.mp3").build()))
            compose.setContent { MaterialTheme {
                MusicPlayerControls(track, player, 60_000, 180_000, false, false, Player.REPEAT_MODE_OFF, 1f,
                    onBack = {}, onEqualizer = { eq++ }, onQueue = { queue++ }, onFavorite = { favorite++ })
            } }
            compose.onNodeWithContentDescription("Перемешать").performClick()
            assertTrue(player.shuffleModeEnabled)
            compose.onNodeWithContentDescription("Повтор выключен").performClick()
            assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
            compose.onNodeWithContentDescription("Эквалайзер").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Очередь").performClick()
            compose.onNodeWithContentDescription("Избранное").performClick()
            assertEquals(1, eq); assertEquals(1, queue); assertEquals(1, favorite)
        } finally { player.release() }
    }
}
