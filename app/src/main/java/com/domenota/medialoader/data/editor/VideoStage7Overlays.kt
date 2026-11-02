package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Same Media3 overlay classes in ExoPlayer preview and Transformer export.
 * There is no fake crossfade: images meet at the source clip boundary.
 */
@OptIn(UnstableApi::class)
internal class VideoTransitionOverlay(
    private val phase: VideoTransitionPhase,
    private val timeOffsetMs: Long,
) : BitmapOverlay() {
    private var image: Bitmap? = null
    private var scale = 1f
    private var paintedCoverage = -1f

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        scale = (1920f / maxOf(videoSize.width, videoSize.height)).coerceAtMost(1f)
        image?.recycle()
        image = Bitmap.createBitmap(
            (videoSize.width * scale).roundToInt().coerceAtLeast(1),
            (videoSize.height * scale).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888)
        paintedCoverage = -1f
        if (phase.type != VideoTransitionType.WIPE_BLACK) {
            image?.eraseColor(if (phase.type == VideoTransitionType.DIP_BLACK)
                Color.BLACK else Color.WHITE)
        }
    }

    private fun coverage(timeUs: Long): Float =
        phase.coverage(timeUs / 1000L - timeOffsetMs)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val bmp = requireNotNull(image)
        if (phase.type == VideoTransitionType.WIPE_BLACK) {
            val amount = coverage(presentationTimeUs).coerceIn(0f, 1f)
            if (abs(paintedCoverage - amount) >= .002f || paintedCoverage < 0f) {
                bmp.eraseColor(Color.TRANSPARENT)
                if (amount > 0f) {
                    val canvas = Canvas(bmp)
                    val paint = Paint().apply { color = Color.BLACK }
                    if (phase.incoming)
                        canvas.drawRect(bmp.width * (1f - amount), 0f,
                            bmp.width.toFloat(), bmp.height.toFloat(), paint)
                    else canvas.drawRect(0f, 0f, bmp.width * amount,
                        bmp.height.toFloat(), paint)
                }
                paintedCoverage = amount
            }
        }
        return bmp
    }

    override fun getOverlaySettings(presentationTimeUs: Long): StaticOverlaySettings {
        val amount = coverage(presentationTimeUs).coerceIn(0f, 1f)
        val alpha = if (phase.type == VideoTransitionType.WIPE_BLACK) 1f
            else if (phase.type == VideoTransitionType.FLASH)
                amount * amount
            else amount
        return StaticOverlaySettings.Builder()
            .setScale(1f / scale, 1f / scale)
            .setAlphaScale(alpha)
            .build()
    }

    override fun release() {
        try { super.release() } finally { image?.recycle(); image = null }
    }
}

/** Clip-local filter treatments rendered above existing Media3 color grading. */
@OptIn(UnstableApi::class)
internal class VideoSpecialFxOverlay(
    private val effect: VideoSpecialFxDraft,
    private val timeOffsetMs: Long,
) : BitmapOverlay() {
    private var image: Bitmap? = null
    private var scale = 1f

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        scale = (1920f / maxOf(videoSize.width, videoSize.height)).coerceAtMost(1f)
        image?.recycle()
        val bmp = Bitmap.createBitmap(
            (videoSize.width * scale).roundToInt().coerceAtLeast(1),
            (videoSize.height * scale).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888)
        image = bmp
        bmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bmp)
        val width = bmp.width.toFloat()
        val height = bmp.height.toFloat()
        VideoSpecialFxPainter.draw(canvas, bmp.width, bmp.height, effect)
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = requireNotNull(image)

    override fun getOverlaySettings(presentationTimeUs: Long): StaticOverlaySettings {
        val alpha = if (effect.type == VideoSpecialFxType.PULSE) {
            val seconds = (presentationTimeUs / 1000L - timeOffsetMs).coerceAtLeast(0L) / 1000.0
            ((1.0 - cos(seconds * 2.0 * PI * 2.0)) * .22f * effect.intensity)
                .toFloat().coerceIn(0f, 1f)
        } else 1f
        return StaticOverlaySettings.Builder()
            .setScale(1f / scale, 1f / scale)
            .setAlphaScale(alpha)
            .build()
    }

    override fun release() {
        try { super.release() } finally { image?.recycle(); image = null }
    }
}
