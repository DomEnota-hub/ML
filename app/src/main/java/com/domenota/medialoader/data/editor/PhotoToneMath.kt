package com.domenota.medialoader.data.editor

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Nonlinear tone adjustments shared by downsized preview and source-resolution output. */
object PhotoToneMath {
    fun tonePixel(argb: Int, settings: PhotoColorAdjustments): Int {
        val alpha = Color.alpha(argb)
        var r = Color.red(argb) / 255f
        var g = Color.green(argb) / 255f
        var b = Color.blue(argb) / 255f
        val luminance = (.2126f * r + .7152f * g + .0722f * b).coerceIn(0f, 1f)
        val sh = settings.shadows / 100f
        val hi = settings.highlights / 100f
        val shadowW = (1f - luminance).pow(2)
        val highlightW = luminance.pow(2)
        // Positive shadows recover dark areas; negative highlights recover bright ones.
        val shadowShift = if (sh >= 0f)
            sh * shadowW * (1f - luminance) * .62f
        else sh * shadowW * luminance * .70f
        val highlightShift = if (hi >= 0f)
            hi * highlightW * (1f - luminance) * .58f
        else hi * highlightW * luminance * .46f
        val shift = shadowShift + highlightShift
        val floor = settings.blackPoint / 100f * .25f
        val gamma = 2f.pow(-settings.curve / 100f)
        fun adjust(v: Float): Float {
            val shifted = (v + shift - floor * (1f - v)).coerceIn(0f, 1f)
            return shifted.pow(gamma).coerceIn(0f, 1f)
        }
        r = adjust(r); g = adjust(g); b = adjust(b)
        val active = settings.hsl.filterValues { !it.isNeutral() }
        if (active.isNotEmpty()) {
            val hsl = rgbToHsl(r, g, b)
            var h = hsl[0]; var sat = hsl[1]; var light = hsl[2]
            var hueDelta = 0f; var satDelta = 0f; var lightDelta = 0f
            for ((range, correction) in active) {
                // A hue can influence adjacent bands smoothly, with no visible color seams.
                val distance = circularDistance(h, range.center)
                val weight = (1f - distance / 60f).coerceIn(0f, 1f)
                hueDelta += correction.hue * .36f * weight
                satDelta += correction.saturation / 100f * weight
                lightDelta += correction.lightness / 100f * weight
            }
            h = (h + hueDelta % 360f + 360f) % 360f
            sat = (sat * (1f + satDelta)).coerceIn(0f, 1f)
            light = if (lightDelta >= 0f) light + (1f - light) * lightDelta
                else light * (1f + lightDelta)
            val rgb = hslToRgb(h, sat, light.coerceIn(0f, 1f))
            r = rgb[0]; g = rgb[1]; b = rgb[2]
        }
        return Color.argb(alpha, (r * 255f).toInt().coerceIn(0, 255),
            (g * 255f).toInt().coerceIn(0, 255), (b * 255f).toInt().coerceIn(0, 255))
    }

    fun circularDistance(a: Float, b: Float): Float {
        val d = abs(((a - b) % 360f + 360f) % 360f)
        return min(d, 360f - d)
    }

    fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val maximum = max(r, max(g, b))
        val minimum = min(r, min(g, b))
        val delta = maximum - minimum
        val light = (maximum + minimum) / 2f
        if (delta < 1e-6f) return floatArrayOf(0f, 0f, light)
        val sat = delta / (1f - abs(2f * light - 1f)).coerceAtLeast(1e-6f)
        val hue = when (maximum) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return floatArrayOf((hue + 360f) % 360f, sat.coerceIn(0f, 1f), light)
    }

    fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        val chroma = (1f - abs(2f * l - 1f)) * s
        val segment = (h % 360f + 360f) % 360f / 60f
        val x = chroma * (1f - abs(segment % 2f - 1f))
        val values = when {
            segment < 1f -> floatArrayOf(chroma, x, 0f)
            segment < 2f -> floatArrayOf(x, chroma, 0f)
            segment < 3f -> floatArrayOf(0f, chroma, x)
            segment < 4f -> floatArrayOf(0f, x, chroma)
            segment < 5f -> floatArrayOf(x, 0f, chroma)
            else -> floatArrayOf(chroma, 0f, x)
        }
        val m = l - chroma / 2f
        return floatArrayOf((values[0] + m).coerceIn(0f, 1f),
            (values[1] + m).coerceIn(0f, 1f), (values[2] + m).coerceIn(0f, 1f))
    }
}
