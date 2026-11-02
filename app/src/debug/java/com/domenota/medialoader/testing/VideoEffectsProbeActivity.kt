package com.domenota.medialoader.testing

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.atomic.AtomicLong

/** Synthetic codec test host. Never included in the release APK. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoEffectsProbeActivity : Activity() {
    lateinit var videoSurface: SurfaceView
    lateinit var player: ExoPlayer
    val frameTimeUs = AtomicLong(0)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        videoSurface = SurfaceView(this)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(videoSurface, FrameLayout.LayoutParams(320, 180, Gravity.CENTER))
        })
        player = ExoPlayer.Builder(this).build().apply {
            setVideoSurfaceView(videoSurface)
            setVideoEffects(emptyList()) // Required before the FIRST prepare, as in the editor.
            setVideoFrameMetadataListener { pts, _, _, _ -> frameTimeUs.set(pts) }
        }
    }
    override fun onDestroy() { player.release(); super.onDestroy() }
}
