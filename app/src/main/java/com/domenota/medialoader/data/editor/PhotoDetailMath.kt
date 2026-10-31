package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Spatial detail correction, O(width * height * radius) with bounded scanline
 * buffers. Never allocates a full-size temporary blur or full-image IntArray.
 */
object PhotoDetailMath {
    fun apply(source: Bitmap, sharpness: Float, clarity: Float): Bitmap {
        require(sharpness.isFinite() && clarity.isFinite())
        require(sharpness in -100f..100f && clarity in -100f..100f)
        if (sharpness == 0f && clarity == 0f) return source
        var owned: Bitmap? = null
        try {
            var input = source
            if (sharpness != 0f) {
                owned = pass(input, sharpness / 100f * .95f, 1, false)
                input = requireNotNull(owned)
            }
            if (clarity != 0f) {
                val next = pass(input, clarity / 100f * .85f, 5, true)
                if (owned !== source) owned?.recycle()
                owned = next
            }
            return requireNotNull(owned)
        } catch (error: Throwable) {
            if (owned !== source) owned?.recycle()
            throw error
        }
    }

    private fun horizontalBlur(row: IntArray, radius: Int): IntArray {
        val width = row.size
        val result = IntArray(width)
        var a = 0L; var r = 0L; var g = 0L; var b = 0L
        fun add(index: Int, sign: Int) {
            val c = row[index.coerceIn(0, width - 1)]
            a += (c ushr 24 and 255).toLong() * sign
            r += (c ushr 16 and 255).toLong() * sign
            g += (c ushr 8 and 255).toLong() * sign
            b += (c and 255).toLong() * sign
        }
        for (k in -radius..radius) add(k, 1)
        val denominator = 2 * radius + 1
        for (x in 0 until width) {
            result[x] = ((a / denominator).toInt() shl 24) or
                ((r / denominator).toInt() shl 16) or
                ((g / denominator).toInt() shl 8) or (b / denominator).toInt()
            add(x - radius, -1)
            add(x + radius + 1, 1)
        }
        return result
    }

    private fun pass(source: Bitmap, amount: Float, radius: Int,
        luminanceOnly: Boolean): Bitmap {
        val w = source.width; val h = source.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(w)
            val raw = IntArray(w)
            val calculated = IntArray(w)
            val blurRows = Array(2 * radius + 1) { IntArray(w) }
            fun readBlur(y: Int, slot: Int) {
                source.getPixels(row, 0, w, 0, y.coerceIn(0, h - 1), w, 1)
                horizontalBlur(row, radius).copyInto(blurRows[slot])
            }
            for (i in -radius..radius)
                readBlur(i, (i + radius) % blurRows.size)
            var head = 0
            val n = blurRows.size
            for (y in 0 until h) {
                source.getPixels(raw, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    var blurR = 0; var blurG = 0; var blurB = 0
                    for (i in 0 until n) {
                        val pixel = blurRows[i][x]
                        blurR += pixel ushr 16 and 255
                        blurG += pixel ushr 8 and 255
                        blurB += pixel and 255
                    }
                    val pixel = raw[x]
                    val r = pixel ushr 16 and 255
                    val g = pixel ushr 8 and 255
                    val b = pixel and 255
                    val br = blurR.toFloat() / n
                    val bg = blurG.toFloat() / n
                    val bb = blurB.toFloat() / n
                    // Clarity is a low-frequency contrast lift that leaves the hue
                    // intact; sharpness sharpens each channel at a smaller radius.
                    val delta = if (luminanceOnly) {
                        val current = .2126f * r + .7152f * g + .0722f * b
                        val blurred = .2126f * br + .7152f * bg + .0722f * bb
                        val detail = current - blurred
                        // Discard tiny differences to avoid amplifying sensor noise.
                        if (abs(detail) < 2.8f) 0f else detail * amount
                    } else 0f
                    fun clamp(v: Float) = v.roundToInt().coerceIn(0, 255)
                    val nr = clamp(r + if (luminanceOnly) delta else (r - br) * amount)
                    val ng = clamp(g + if (luminanceOnly) delta else (g - bg) * amount)
                    val nb = clamp(b + if (luminanceOnly) delta else (b - bb) * amount)
                    calculated[x] = (pixel and (0xff shl 24)) or
                        (nr shl 16) or (ng shl 8) or nb
                }
                output.setPixels(calculated, 0, w, 0, y, w, 1)
                val reused = head
                head = (head + 1) % n
                readBlur(y + radius + 1, reused)
            }
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }
}
