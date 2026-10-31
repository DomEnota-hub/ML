package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Deterministic image-space post-processing. No native dependency or branded assets.
 * Uses bounded scanlines for 12–48 MP photos. Glow blurs a <= 320px working map,
 * rather than allocating additional full-resolution images.
 */
object PhotoStylizeMath {
    fun needsWork(v: PhotoColorAdjustments): Boolean {
        val strength = v.effectStrength / 100f
        return v.vignette > 0f || v.glow > 0f || v.dispersion > 0f ||
            (strength > 0f && (v.effect.fade > 0f || v.effect.grain > 0f))
    }

    fun apply(source: Bitmap, v: PhotoColorAdjustments): Bitmap {
        if (!needsWork(v)) return source
        val width = source.width
        val height = source.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val strength = v.effectStrength / 100f
            val fade = v.effect.fade / 100f * strength
            val grain = v.effect.grain / 100f * strength
            val vignette = v.vignette / 100f
            val glow = v.glow / 100f
            val dispersion = v.dispersion / 100f
            val offset = (min(width, height) * (.002f + v.dispersionSize / 100f * .012f))
                .roundToInt().coerceAtLeast(1)
            val blurred = if (glow > 0f) makeGlowMap(source, v.glowSize) else null
            val sw = blurred?.first ?: 0
            val sh = blurred?.second ?: 0
            val glowMap = blurred?.third
            val row = IntArray(width)
            val output = IntArray(width)
            for (y in 0 until height) {
                source.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    val pixel = row[x]
                    val alpha = pixel ushr 24 and 255
                    var r = pixel ushr 16 and 255f.toInt()
                    var g = pixel ushr 8 and 255
                    var b = pixel and 255
                    if (fade > 0f) {
                        r = (r * (1f - fade * .18f) + 23f * fade).roundToInt()
                        g = (g * (1f - fade * .18f) + 23f * fade).roundToInt()
                        b = (b * (1f - fade * .18f) + 23f * fade).roundToInt()
                    }
                    if (glow > 0f && glowMap != null) {
                        val mx = (x.toLong() * sw / width).toInt().coerceIn(0, sw - 1)
                        val my = (y.toLong() * sh / height).toInt().coerceIn(0, sh - 1)
                        val soft = glowMap[my * sw + mx]
                        val lr = soft ushr 16 and 255
                        val lg = soft ushr 8 and 255
                        val lb = soft and 255
                        val luminance = (.213f * lr + .715f * lg + .072f * lb) / 255f
                        val blend = glow * luminance * luminance * .57f
                        r = (r + (lr - r) * blend + lr * glow * luminance * .13f).roundToInt()
                        g = (g + (lg - g) * blend + lg * glow * luminance * .13f).roundToInt()
                        b = (b + (lb - b) * blend + lb * glow * luminance * .13f).roundToInt()
                    }
                    if (dispersion > 0f) {
                        val redSample = row[(x - offset).coerceIn(0, width - 1)] ushr 16 and 255
                        val blueSample = row[(x + offset).coerceIn(0, width - 1)] and 255
                        r = (r * (1f - dispersion) + redSample * dispersion).roundToInt()
                        b = (b * (1f - dispersion) + blueSample * dispersion).roundToInt()
                    }
                    if (vignette > 0f) {
                        val dx = (2f * x / max(1, width - 1) - 1f)
                        val dy = (2f * y / max(1, height - 1) - 1f)
                        val distance = hypot(dx, dy) / 1.41421356f
                        val onset = .15f + v.vignetteSize / 100f * .55f
                        val t = ((distance - onset) / (1f - onset)).coerceIn(0f, 1f)
                        val falloff = t * t * (3f - 2f * t)
                        val gain = 1f - vignette * .85f * falloff
                        r = (r * gain).roundToInt()
                        g = (g * gain).roundToInt()
                        b = (b * gain).roundToInt()
                    }
                    if (grain > 0f) {
                        // Stable, repeatable noise across preview and export.
                        val hash = (x * 73856093) xor (y * 19349663) xor 0x35A7C29
                        val noise = (((hash xor (hash ushr 13)) and 255) - 128) * grain * .37f
                        r = (r + noise).roundToInt()
                        g = (g + noise).roundToInt()
                        b = (b + noise).roundToInt()
                    }
                    output[x] = (alpha shl 24) or (r.coerceIn(0, 255) shl 16) or
                        (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
                }
                result.setPixels(output, 0, width, 0, y, width, 1)
            }
            return result
        } catch (error: Throwable) {
            result.recycle()
            throw error
        }
    }

    /** Three fast box passes at capped resolution approximate soft Gaussian bloom. */
    private fun makeGlowMap(source: Bitmap, radiusPercent: Float): Triple<Int, Int, IntArray> {
        val scale = min(1f, 320f / max(source.width, source.height))
        val w = max(1, (source.width * scale).roundToInt())
        val h = max(1, (source.height * scale).roundToInt())
        val bitmap = Bitmap.createScaledBitmap(source, w, h, true)
        val data = IntArray(w * h)
        try {
            bitmap.getPixels(data, 0, w, 0, 0, w, h)
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
        val tmp = IntArray(data.size)
        val radius = (2f + radiusPercent / 100f * 21f).roundToInt()
        repeat(2) {
            blurPass(data, tmp, w, h, radius, true)
            blurPass(tmp, data, w, h, radius, false)
        }
        return Triple(w, h, data)
    }

    private fun blurPass(input: IntArray, output: IntArray, w: Int, h: Int,
        radius: Int, horizontal: Boolean) {
        val primarySize = if (horizontal) w else h
        val secondarySize = if (horizontal) h else w
        fun index(p: Int, s: Int) = if (horizontal) s * w + p else p * w + s
        val n = radius * 2 + 1
        for (s in 0 until secondarySize) {
            var a = 0L; var red = 0L; var green = 0L; var blue = 0L
            fun adjust(at: Int, sign: Int) {
                val color = input[index(at.coerceIn(0, primarySize - 1), s)]
                a += (color ushr 24 and 255).toLong() * sign
                red += (color ushr 16 and 255).toLong() * sign
                green += (color ushr 8 and 255).toLong() * sign
                blue += (color and 255).toLong() * sign
            }
            for (d in -radius..radius) adjust(d, 1)
            for (p in 0 until primarySize) {
                output[index(p, s)] = ((a / n).toInt() shl 24) or
                    ((red / n).toInt() shl 16) or ((green / n).toInt() shl 8) or
                    (blue / n).toInt()
                adjust(p - radius, -1)
                adjust(p + radius + 1, 1)
            }
        }
    }
}
