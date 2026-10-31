package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Color changes are non-destructive and are applied to the original-resolution output.
 * Preview and export use exactly the same matrix, avoiding a different-looking saved copy.
 */
data class PhotoColorAdjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val exposure: Float = 0f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val blackPoint: Float = 0f,
    val curve: Float = 0f,
    val hsl: Map<HslRange, HslCorrection> = emptyMap(),
    val effect: PhotoEffect = PhotoEffect.NONE,
) {
    init {
        require(listOf(brightness, contrast, saturation, warmth, exposure, shadows, highlights,
            blackPoint, curve).all { it.isFinite() && it in -100f..100f })
        require(hsl.size <= HslRange.entries.size)
    }
    fun isNeutral() = this == PhotoColorAdjustments()
}

enum class HslRange(val label: String, val center: Float) {
    RED("Красный", 0f), ORANGE("Оранжевый", 30f), YELLOW("Жёлтый", 60f),
    GREEN("Зелёный", 120f), CYAN("Голубой", 180f), BLUE("Синий", 240f),
    PURPLE("Фиолетовый", 275f), MAGENTA("Пурпурный", 320f)
}
data class HslCorrection(
    val hue: Float = 0f, val saturation: Float = 0f, val lightness: Float = 0f,
) {
    init { require(listOf(hue, saturation, lightness).all { it.isFinite() && it in -100f..100f }) }
    fun isNeutral() = hue == 0f && saturation == 0f && lightness == 0f
}

enum class PhotoEffect(val label: String) {
    NONE("Оригинал"), MONO("Ч/Б"), VIVID("Яркий"), WARM("Тёплый"), COOL("Холодный")
}

object PhotoColorMath {
    fun matrix(values: PhotoColorAdjustments): ColorMatrix {
        val presetSaturation = when (values.effect) {
            PhotoEffect.MONO -> 0f
            PhotoEffect.VIVID -> 1.3f
            else -> 1f
        }
        val presetWarmth = when (values.effect) {
            PhotoEffect.WARM -> 20f
            PhotoEffect.COOL -> -20f
            else -> 0f
        }
        val saturation = if (values.effect == PhotoEffect.MONO) 0f
            else (presetSaturation * (1f + values.saturation / 100f)).coerceIn(0f, 2.6f)
        val inverse = 1f - saturation
        val contrast = (1f + values.contrast / 100f * 0.8f).coerceIn(0.2f, 1.8f)
        val exposure = 2f.pow(values.exposure / 100f)
        val warmth = (values.warmth + presetWarmth).coerceIn(-100f, 100f) / 350f
        val redGain = contrast * exposure * (1f + warmth)
        val greenGain = contrast * exposure
        val blueGain = contrast * exposure * (1f - warmth)
        val offset = 128f * (1f - contrast) + values.brightness * 1.25f
        val red = floatArrayOf(inverse * .213f + saturation, inverse * .715f, inverse * .072f)
        val green = floatArrayOf(inverse * .213f, inverse * .715f + saturation, inverse * .072f)
        val blue = floatArrayOf(inverse * .213f, inverse * .715f, inverse * .072f + saturation)
        return ColorMatrix(floatArrayOf(
            red[0] * redGain, red[1] * redGain, red[2] * redGain, 0f, offset,
            green[0] * greenGain, green[1] * greenGain, green[2] * greenGain, 0f, offset,
            blue[0] * blueGain, blue[1] * blueGain, blue[2] * blueGain, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    fun apply(source: Bitmap, values: PhotoColorAdjustments): Bitmap {
        if (values.isNeutral()) return source
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(source, 0f, 0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix(values))
            })
        if (values.shadows != 0f || values.highlights != 0f || values.blackPoint != 0f ||
            values.curve != 0f || values.hsl.values.any { !it.isNeutral() }) {
            // Bounded row buffers: avoid another width*height int array for 12–48 MP photos.
            val rows = 24
            val colors = IntArray(output.width * rows)
            var top = 0
            while (top < output.height) {
                val count = min(rows, output.height - top)
                output.getPixels(colors, 0, output.width, 0, top, output.width, count)
                for (i in 0 until count * output.width) {
                    colors[i] = PhotoToneMath.tonePixel(colors[i], values)
                }
                output.setPixels(colors, 0, output.width, 0, top, output.width, count)
                top += count
            }
        }
        return output
    }
}
