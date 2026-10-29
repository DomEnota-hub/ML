package com.domenota.medialoader.data.playback

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.ForwardingPlayer
import androidx.media3.exoplayer.ExoPlayer
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class FloatingVideoSeekBarTest {
    @Test fun scrubbingNeverDragsWindowOrChangesPlayIntentAndDetachingStopsUpdates() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val base = ExoPlayer.Builder(activity).build()
        var position = 1000L; var writes = 0; var reads = 0; var durationMs = 100_000L
        val player = object : ForwardingPlayer(base) {
            override fun getDuration(): Long { reads++; return durationMs }
            override fun getCurrentPosition(): Long = position
            override fun isCurrentMediaItemSeekable(): Boolean = true
            override fun seekTo(positionMs: Long) { writes++; position = positionMs }
        }
        var parentTouches = 0
        val parent = FrameLayout(activity).apply { setOnTouchListener { _, _ -> parentTouches++; true } }
        val states = mutableListOf<Boolean>()
        val bar = FloatingVideoSeekBar(activity, player, { states += it })
        parent.addView(bar, FrameLayout.LayoutParams(300, 40))
        activity.setContentView(parent)
        parent.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 300, 200)
        fun touch(action: Int, x: Float) {
            val event = MotionEvent.obtain(0, 10, action, x, 30f, 0)
            parent.dispatchTouchEvent(event); event.recycle()
        }
        try {
            base.playWhenReady = true
            touch(MotionEvent.ACTION_DOWN, 30f)
            touch(MotionEvent.ACTION_MOVE, 240f)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            assertEquals(0, writes)
            touch(MotionEvent.ACTION_UP, 240f)
            assertEquals(80_000L, position); assertEquals(1, writes)
            assertTrue(base.playWhenReady); assertEquals(0, parentTouches)
            assertEquals(listOf(true, false), states)
            base.pause()
            touch(MotionEvent.ACTION_DOWN, 150f); touch(MotionEvent.ACTION_UP, 150f)
            assertEquals(50_000L, position); assertFalse(base.playWhenReady)
            touch(MotionEvent.ACTION_DOWN, 150f); touch(MotionEvent.ACTION_CANCEL, 150f)
            assertEquals(2, writes)
            durationMs = androidx.media3.common.C.TIME_UNSET
            touch(MotionEvent.ACTION_DOWN, 90f); touch(MotionEvent.ACTION_UP, 90f)
            assertEquals(2, writes)
            parent.removeView(bar)
            val detachedReads = reads
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(detachedReads, reads)
        } finally { base.release(); activity.finish() }
    }
}
