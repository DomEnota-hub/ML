package com.domenota.medialoader.data.editor

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoPreviewQueueReuseTest {
    @Test fun queueEditsKeepPlayerAndDeferredPreparationUntilSurfaceBinding() {
        var created = 0
        val session = VideoEditorPreviewSession(RuntimeEnvironment.getApplication(),
            deferPreparation = true, onPlayerCreated = { created++ })
        try {
            val player = session.player
            repeat(3) { session.replacePlaylist(emptyList(), 0, 0) }
            assertSame(player, session.player)
            assertEquals(1, created)
            assertEquals(Player.STATE_IDLE, session.player.playbackState)
        } finally { session.release() }
    }
}
