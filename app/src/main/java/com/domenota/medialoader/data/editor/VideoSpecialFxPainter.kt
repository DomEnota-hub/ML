package com.domenota.medialoader.data.editor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * One deterministic renderer for Media3's composited video overlay AND effect
 * card thumbnails. No duplicate aesthetic approximations in the UI layer.
 *
 * All coordinates are dimension-relative, and bitmap allocation belongs to
 * the caller. The render code is used on a pre-sized canvas (not every Compose
 * recomposition). No modification of the source video is performed here.
 */
internal object VideoSpecialFxPainter {
    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        draft: VideoSpecialFxDraft,
        stillPreview: Boolean = false,
    ) {
        require(width > 0 && height > 0)
        val w = width.toFloat()
        val h = height.toFloat()
        val strength = draft.intensity.coerceIn(0f, 1f)
        if (strength == 0f) return
        fun alpha(full: Int): Int = (full * strength).roundToInt().coerceIn(0, 255)
        fun paint(red: Int, green: Int, blue: Int, opacity: Int): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(alpha(opacity), red, green, blue)
            }
        fun stroke(color: Int, size: Float): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                strokeWidth = size
            }
        when (draft.type) {
            VideoSpecialFxType.NONE -> Unit
            VideoSpecialFxType.VIGNETTE -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(w * .5f, h * .5f, hypot(w, h) * .58f,
                        intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT,
                            Color.argb(alpha(220), 0, 0, 0)),
                        floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, 0f, w, h, p)
            }
            VideoSpecialFxType.SCANLINES -> {
                val p = paint(0, 0, 0, 170)
                val step = (h / 125f).coerceAtLeast(4f)
                var y = 1f
                while (y < h) {
                    canvas.drawRect(0f, y, w, y + (step / 4f).coerceAtLeast(1f), p)
                    y += step
                }
            }
            VideoSpecialFxType.CINEMA_BARS -> {
                val p = Paint().apply { color = Color.BLACK }
                val band = h * .13f * strength
                canvas.drawRect(0f, 0f, w, band, p)
                canvas.drawRect(0f, h - band, w, h, p)
            }
            VideoSpecialFxType.PULSE -> {
                // Media3 animates alpha through getOverlaySettings; thumbnails
                // show a representative lit frame of the same white overlay.
                val p = Paint().apply {
                    color = Color.argb(if (stillPreview) alpha(86) else 255,
                        255, 255, 255)
                }
                canvas.drawRect(0f, 0f, w, h, p)
            }
            VideoSpecialFxType.FILM_GRAIN -> {
                val random = Random(0x7F01)
                val count = (width.toLong() * height / 150L).toInt().coerceIn(18, 25000)
                val radius = (minOf(w, h) / 330f).coerceIn(.65f, 3.5f)
                val white = paint(255, 255, 255, 135)
                val black = paint(0, 0, 0, 115)
                repeat(count) {
                    canvas.drawCircle(random.nextFloat() * w,
                        random.nextFloat() * h, radius,
                        if (random.nextBoolean()) white else black)
                }
            }
            VideoSpecialFxType.OLD_FILM -> {
                canvas.drawRect(0f, 0f, w, h, paint(140, 100, 52, 90))
                val random = Random(0x0F15)
                val scratches = paint(255, 247, 221, 185).apply {
                    strokeWidth = (w / 340f).coerceIn(.7f, 2.8f)
                }
                repeat(12) {
                    val x = random.nextFloat() * w
                    val y = random.nextFloat() * h
                    canvas.drawLine(x, y, x + (random.nextFloat() - .5f) * w * .018f,
                        (y + h * (0.05f + random.nextFloat() * .5f)).coerceAtMost(h),
                        scratches)
                }
                canvas.drawRect(0f, 0f, w, h * .06f, paint(0, 0, 0, 160))
            }
            VideoSpecialFxType.VHS -> {
                canvas.drawRect(0f, 0f, w, h, paint(12, 31, 70, 52))
                val dark = paint(0, 0, 0, 145)
                val teal = paint(25, 245, 232, 90)
                val magenta = paint(255, 25, 204, 88)
                val step = (h / 65f).coerceAtLeast(3f)
                var y = 1f
                while (y < h) {
                    canvas.drawRect(0f, y, w, y + 1.5f, dark)
                    y += step
                }
                canvas.drawRect(0f, h * .33f, w, h * .35f, teal)
                canvas.drawRect(0f, h * .7f, w, h * .715f, magenta)
            }
            VideoSpecialFxType.GLITCH -> {
                val random = Random(0x61C1)
                val colors = listOf(
                    paint(255, 21, 130, 132),
                    paint(0, 237, 255, 132),
                    paint(30, 0, 0, 145))
                repeat(12) { i ->
                    val y = (i * .08f + random.nextFloat() * .06f) * h
                    val span = (.12f + random.nextFloat() * .5f) * w
                    val x = random.nextFloat() * (w - span)
                    val th = (h * (.006f + random.nextFloat() * .023f))
                        .coerceAtLeast(1f)
                    canvas.drawRect(x, y, x + span, y + th, colors[i % colors.size])
                }
            }
            VideoSpecialFxType.NEON_FRAME -> {
                val pad = (minOf(w, h) * .035f).coerceAtLeast(3f)
                val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(alpha(210), 39, 224, 255)
                    strokeWidth = (minOf(w, h) * .025f).coerceAtLeast(2f)
                    style = Paint.Style.STROKE
                }
                val secondary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(alpha(160), 240, 51, 231)
                    strokeWidth = (minOf(w, h) * .012f).coerceAtLeast(1f)
                    style = Paint.Style.STROKE
                }
                canvas.drawRect(pad, pad, w - pad, h - pad, accent)
                canvas.drawRect(pad * 1.9f, pad * 1.9f,
                    w - pad * 1.9f, h - pad * 1.9f, secondary)
            }
            VideoSpecialFxType.SPOTLIGHT -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(w * .65f, h * .34f, hypot(w, h) * .63f,
                        intArrayOf(Color.TRANSPARENT,
                            Color.argb(alpha(140), 0, 0, 0),
                            Color.argb(alpha(255), 0, 0, 0)),
                        floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, 0f, w, h, p)
            }
        }
    }
}
