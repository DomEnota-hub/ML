package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import kotlin.math.roundToInt
import kotlin.random.Random

/** RGB preset-specific grain is rendered in BOTH Media3 preview and MP4 Transformer. */
@OptIn(UnstableApi::class)
internal class VideoPhotoGrainOverlay(
    private val grainPercent: Float,
    private val filterStrength: Float,
) : BitmapOverlay() {
    private var bitmap: Bitmap? = null
    private var scale = 1f

    init {
        require(grainPercent.isFinite() && grainPercent in 0f..100f)
        require(filterStrength.isFinite() && filterStrength in 0f..1f)
    }

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        scale = (640f / maxOf(videoSize.width, videoSize.height)).coerceAtMost(1f)
        bitmap?.recycle()
        val width = (videoSize.width * scale).roundToInt().coerceAtLeast(1)
        val height = (videoSize.height * scale).roundToInt().coerceAtLeast(1)
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val values = makePixels(width, height, grainPercent, filterStrength)
            setPixels(values, 0, width, 0, 0, width, height)
        }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = requireNotNull(bitmap)

    override fun getOverlaySettings(presentationTimeUs: Long): StaticOverlaySettings =
        StaticOverlaySettings.Builder().setScale(1f / scale, 1f / scale).build()

    override fun release() {
        try { super.release() } finally { bitmap?.recycle(); bitmap = null }
    }

    companion object {
        /** Deterministic texture: avoid random flicker between adjacent video frames. */
        fun makePixels(width: Int, height: Int, grain: Float, strength: Float): IntArray {
            require(width in 1..4096 && height in 1..4096)
            require(grain.isFinite() && grain in 0f..100f)
            require(strength.isFinite() && strength in 0f..1f)
            val alpha = (100f * grain / 100f * strength).toInt().coerceIn(0, 100)
            val rng = Random(0x2117)
            return IntArray(width * height) {
                if (rng.nextFloat() > .42f) Color.TRANSPARENT
                else {
                    val white = rng.nextBoolean()
                    Color.argb(alpha, if (white) 255 else 0,
                        if (white) 255 else 0, if (white) 255 else 0)
                }
            }
        }
    }
}
