package com.domenota.medialoader.data.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class FloatingVideoHandoffTest {
    @Test fun exactIdentityRejectsDifferentFilesEvenWhenIdOrUriMatches() {
        val request = FloatingVideoHandoffRequest("token", "video-1", "content://media/video/42")
        assertTrue(request.matches("video-1", "content://media/video/42"))
        assertFalse(request.matches("video-1", "content://media/video/43"))
        assertFalse(request.matches("video-2", "content://media/video/42"))
        assertFalse(request.matches("video-1", null))
    }
    @Test fun pausedSourceTransfersPositionRepeatSpeedAndVolumeWithoutAutoplay() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val base = ExoPlayer.Builder(activity).build()
        try {
            base.repeatMode = Player.REPEAT_MODE_ONE
            base.setPlaybackSpeed(1.5f)
            base.volume = .4f
            base.playWhenReady = false
            val source = object : ForwardingPlayer(base) {
                override fun getCurrentPosition(): Long = 56_789L
                override fun getPlaybackState(): Int = Player.STATE_READY
            }
            val snapshot = FloatingPlaybackState.capture(source)
            assertEquals(56_789L, snapshot.positionMs)
            assertFalse(snapshot.play)
            var sought: Long? = null
            val destination = object : ForwardingPlayer(base) {
                override fun seekTo(positionMs: Long) { sought = positionMs }
            }
            base.repeatMode = Player.REPEAT_MODE_OFF
            base.setPlaybackSpeed(1f)
            base.volume = 1f
            base.playWhenReady = true
            snapshot.prepareDestination(destination)
            assertEquals(56_789L, sought)
            assertEquals(Player.REPEAT_MODE_ONE, base.repeatMode)
            assertEquals(1.5f, base.playbackParameters.speed, .001f)
            assertEquals(.4f, base.volume, .001f)
            assertFalse(base.playWhenReady)
        } finally { base.release(); activity.finish() }
    }
    @Test fun endedSourceDoesNotAutoPlayEvenWithPlayIntent() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val base = ExoPlayer.Builder(activity).build()
        try {
            base.playWhenReady = true
            val ended = object : ForwardingPlayer(base) {
                override fun getPlaybackState(): Int = Player.STATE_ENDED
            }
            assertFalse(FloatingPlaybackState.capture(ended).play)
        } finally { base.release(); activity.finish() }
    }
}
