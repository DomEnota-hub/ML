package com.domenota.medialoader.data.editor

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Nonlinear tone adjustments shared by downsized preview and source-resolution output. */
object PhotoToneMath {
    data class Prepared(
        val shadows: Float,
        val highlights: Float,
        val blackPoint: Float,
        val gamma: Float,
        val vibrance: Float,
        val curves: PhotoCurveMath.Prepared?,
        val activeHsl: List<Pair<HslRange, HslCorrection>>,
    )

    /** Compute slider coefficients and active HSL bands ONCE, not for every pixel. */
    fun prepare(settings: PhotoColorAdjustments) = Prepared(
        shadows = settings.shadows / 100f,
        highlights = settings.highlights / 100f,
        blackPoint = settings.blackPoint / 100f * .25f,
        gamma = 2f.pow(-settings.curve / 100f),
        vibrance = settings.vibrance / 100f,
        curves = if (settings.rgbCurves.isNeutral()) null
            else PhotoCurveMath.prepare(settings.rgbCurves),
        activeHsl = settings.hsl.entries.filter { !it.value.isNeutral() }
            .map { it.key to it.value },
    )

    fun tonePixel(argb: Int, settings: PhotoColorAdjustments): Int =
        tonePixel(argb, prepare(settings), FloatArray(3))

    fun tonePixel(argb: Int, profile: Prepared, scratch: FloatArray): Int {
        require(scratch.size >= 3)
        val alpha = Color.alpha(argb)
        var r = Color.red(argb) / 255f
        var g = Color.green(argb) / 255f
        var b = Color.blue(argb) / 255f
        val luminance = (.2126f * r + .7152f * g + .0722f * b).coerceIn(0f, 1f)
        val sh = profile.shadows
        val hi = profile.highlights
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
        val floor = profile.blackPoint
        val gamma = profile.gamma
        fun adjust(v: Float): Float {
            val shifted = (v + shift - floor * (1f - v)).coerceIn(0f, 1f)
            return shifted.pow(gamma).coerceIn(0f, 1f)
        }
        r = adjust(r); g = adjust(g); b = adjust(b)
        val active = profile.activeHsl
        if (active.isNotEmpty()) {
            rgbToHslInto(r, g, b, scratch)
            var h = scratch[0]; var sat = scratch[1]; var light = scratch[2]
            var hueDelta = 0f; var satDelta = 0f; var lightDelta = 0f
            for (index in active.indices) {
                val (range, correction) = active[index]
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
            hslToRgbInto(h, sat, light.coerceIn(0f, 1f), scratch)
            r = scratch[0]; g = scratch[1]; b = scratch[2]
        }
        if (profile.vibrance != 0f) {
            rgbToHslInto(r, g, b, scratch)
            val sat = scratch[1]
            // Keep grays neutral; protect already vibrant colors from oversaturation.
            val next = (sat * (1f + profile.vibrance * (1f - sat)))
                .coerceIn(0f, 1f)
            if (next != sat) {
                hslToRgbInto(scratch[0], next, scratch[2], scratch)
                r = scratch[0]; g = scratch[1]; b = scratch[2]
            }
        }
        val curves = profile.curves
        var red = kotlin.math.round(r * 255f).toInt().coerceIn(0, 255)
        var green = kotlin.math.round(g * 255f).toInt().coerceIn(0, 255)
        var blue = kotlin.math.round(b * 255f).toInt().coerceIn(0, 255)
        if (curves != null) {
            red = curves.mapRed(red)
            green = curves.mapGreen(green)
            blue = curves.mapBlue(blue)
        }
        return Color.argb(alpha, red, green, blue)
    }

    fun circularDistance(a: Float, b: Float): Float {
        val d = abs(((a - b) % 360f + 360f) % 360f)
        return min(d, 360f - d)
    }

    fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray =
        FloatArray(3).also { rgbToHslInto(r, g, b, it) }

    private fun rgbToHslInto(r: Float, g: Float, b: Float, out: FloatArray) {
        val maximum = max(r, max(g, b))
        val minimum = min(r, min(g, b))
        val delta = maximum - minimum
        val light = (maximum + minimum) / 2f
        out[2] = light
        if (delta < 1e-6f) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        out[1] = (delta / (1f - abs(2f * light - 1f)).coerceAtLeast(1e-6f))
            .coerceIn(0f, 1f)
        val hue = when (maximum) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        out[0] = (hue + 360f) % 360f
    }

    fun hslToRgb(h: Float, s: Float, l: Float): FloatArray =
        FloatArray(3).also { hslToRgbInto(h, s, l, it) }

    private fun hslToRgbInto(h: Float, s: Float, l: Float, out: FloatArray) {
        val chroma = (1f - abs(2f * l - 1f)) * s
        val segment = (h % 360f + 360f) % 360f / 60f
        val x = chroma * (1f - abs(segment % 2f - 1f))
        var r = 0f; var g = 0f; var b = 0f
        when {
            segment < 1f -> { r = chroma; g = x }
            segment < 2f -> { r = x; g = chroma }
            segment < 3f -> { g = chroma; b = x }
            segment < 4f -> { g = x; b = chroma }
            segment < 5f -> { r = x; b = chroma }
            else -> { r = chroma; b = x }
        }
        val m = l - chroma / 2f
        out[0] = (r + m).coerceIn(0f, 1f)
        out[1] = (g + m).coerceIn(0f, 1f)
        out[2] = (b + m).coerceIn(0f, 1f)
    }
}
