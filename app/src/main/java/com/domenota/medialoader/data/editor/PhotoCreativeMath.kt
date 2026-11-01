package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Independent original-resolution creative adjustments. Both preview and export call
 * PhotoColorMath.apply, which routes through these identical, deterministic passes.
 *
 * Memory is bounded to a few scanlines, never a width*height IntArray. Preserve the
 * source bitmap and each pixel's original alpha.
 */
object PhotoCreativeMath {
    fun denoise(source: Bitmap, amount: Float): Bitmap {
        require(amount.isFinite() && amount in 0f..100f)
        if (amount == 0f) return source
        val w = source.width
        val h = source.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            var top = IntArray(w)
            var middle = IntArray(w)
            var bottom = IntArray(w)
            val rowOut = IntArray(w)
            source.getPixels(top, 0, w, 0, 0, w, 1)
            source.getPixels(middle, 0, w, 0, 0, w, 1)
            source.getPixels(bottom, 0, w, 0, (1).coerceAtMost(h - 1), w, 1)
            val mix = amount / 100f * .9f
            val threshold = 10f + amount * .46f
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val pixel = middle[x]
                    val alpha = pixel ushr 24 and 255
                    if (alpha == 0) {
                        rowOut[x] = pixel
                        continue
                    }
                    val red = pixel ushr 16 and 255
                    val green = pixel ushr 8 and 255
                    val blue = pixel and 255
                    val lum = .2126f * red + .7152f * green + .0722f * blue
                    var weightSum = 2f
                    var sumR = red * 2f
                    var sumG = green * 2f
                    var sumB = blue * 2f
                    for (rowIndex in 0..2) {
                        val neighborRow = when (rowIndex) {
                            0 -> top
                            1 -> middle
                            else -> bottom
                        }
                        for (dx in -1..1) {
                            if (rowIndex == 1 && dx == 0) continue
                            val other = neighborRow[(x + dx).coerceIn(0, w - 1)]
                            val otherAlpha = other ushr 24 and 255
                            if (otherAlpha == 0) continue
                            val nr = other ushr 16 and 255
                            val ng = other ushr 8 and 255
                            val nb = other and 255
                            val otherLum = .2126f * nr + .7152f * ng + .0722f * nb
                            val difference = abs(lum - otherLum)
                            if (difference >= threshold) continue
                            val proximity = 1f - difference / threshold
                            val weight = proximity * proximity * (otherAlpha / 255f)
                            sumR += nr * weight
                            sumG += ng * weight
                            sumB += nb * weight
                            weightSum += weight
                        }
                    }
                    fun mixChannel(value: Int, sum: Float): Int =
                        (value + (sum / weightSum - value) * mix)
                            .roundToInt().coerceIn(0, 255)
                    rowOut[x] = (alpha shl 24) or
                        (mixChannel(red, sumR) shl 16) or
                        (mixChannel(green, sumG) shl 8) or mixChannel(blue, sumB)
                }
                output.setPixels(rowOut, 0, w, 0, y, w, 1)
                val recycled = top
                top = middle
                middle = bottom
                bottom = recycled
                source.getPixels(bottom, 0, w, 0, (y + 2).coerceAtMost(h - 1), w, 1)
            }
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var bits = (x * 73856093) xor (y * 19349663) xor seed
        bits = bits xor (bits ushr 16)
        bits *= -2048144789
        bits = bits xor (bits ushr 13)
        return ((bits and 1023) / 511.5f) - 1f
    }

    fun apply(source: Bitmap, values: PhotoColorAdjustments): Bitmap {
        val posterization = values.posterization / 100f
        val grain = values.grain / 100f
        val noise = values.noiseDegree / 100f
        if (posterization == 0f && grain == 0f && noise == 0f) return source
        val w = source.width
        val h = source.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val input = IntArray(w)
            val result = IntArray(w)
            val levels = (32f - posterization * 29f).roundToInt().coerceIn(3, 32)
            val noiseBlock = (1f + values.noiseSize * .07f).roundToInt().coerceIn(1, 8)
            val roughness = values.noiseRoughness / 100f
            val highlights = values.noiseHighlights / 100f
            fun quantize(channel: Int): Int {
                val level = (channel / 255f * (levels - 1)).roundToInt()
                return (level * 255f / (levels - 1)).roundToInt()
            }
            for (y in 0 until h) {
                source.getPixels(input, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val pixel = input[x]
                    val alpha = pixel ushr 24 and 255
                    if (alpha == 0) {
                        result[x] = pixel
                        continue
                    }
                    var r = pixel ushr 16 and 255
                    var g = pixel ushr 8 and 255
                    var b = pixel and 255
                    if (posterization > 0f) {
                        r = (r + (quantize(r) - r) * posterization).roundToInt()
                        g = (g + (quantize(g) - g) * posterization).roundToInt()
                        b = (b + (quantize(b) - b) * posterization).roundToInt()
                    }
                    if (grain > 0f || noise > 0f) {
                        // Film grain is small and monochromatic. Digital noise can
                        // form larger coarse patches, especially in highlights.
                        val film = if (grain > 0f) hash(x, y, 0x2A387F) * grain * 42f else 0f
                        val digital = if (noise > 0f) {
                            val coarse = hash(x / noiseBlock, y / noiseBlock, 0x458319)
                            val fine = hash(x, y, 0x658317)
                            val luminance = (.2126f * r + .7152f * g + .0722f * b) / 255f
                            val highlightWeight = 1f - highlights +
                                highlights * (.25f + luminance * .75f)
                            (coarse * (1f - roughness) + fine * roughness) *
                                noise * 74f * highlightWeight
                        } else 0f
                        val delta = film + digital
                        r = (r + delta).roundToInt()
                        g = (g + delta).roundToInt()
                        b = (b + delta).roundToInt()
                    }
                    result[x] = (alpha shl 24) or
                        (r.coerceIn(0, 255) shl 16) or
                        (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
                }
                output.setPixels(result, 0, w, 0, y, w, 1)
            }
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }
}
