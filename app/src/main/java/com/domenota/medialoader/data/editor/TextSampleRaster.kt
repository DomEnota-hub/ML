package com.domenota.medialoader.data.editor

import android.graphics.Bitmap
import android.graphics.Color

internal data class TextRaster(val width: Int, val height: Int, val bits: BooleanArray) {
    fun tight(): TextRaster {
        val indices = bits.indices.filter { bits[it] }
        require(indices.isNotEmpty()) { "Надпись не найдена" }
        val left = indices.minOf { it % width }; val right = indices.maxOf { it % width }
        val top = indices.minOf { it / width }; val bottom = indices.maxOf { it / width }
        val w = right - left + 1; val h = bottom - top + 1
        return TextRaster(w, h, BooleanArray(w * h) { bits[(top + it / w) * width + left + it % w] })
    }
    fun normalized(): NormalizedTextShape {
        var left = width; var top = height; var right = -1; var bottom = -1
        bits.forEachIndexed { index, ink -> if (ink) {
            left = minOf(left, index % width); right = maxOf(right, index % width)
            top = minOf(top, index / width); bottom = maxOf(bottom, index / width)
        } }
        require(right >= left && bottom >= top) { "Надпись не найдена" }
        val w = right - left + 1; val h = bottom - top + 1
        val normalized = BooleanArray(256 * 64) { index ->
            val x = left + ((index % 256) * w / 256).coerceAtMost(w - 1)
            val y = top + ((index / 256) * h / 64).coerceAtMost(h - 1)
            bits[y * width + x]
        }
        return NormalizedTextShape(normalized, w.toFloat() / h)
    }
}

internal object TextSampleRaster {
    fun read(bitmap: Bitmap): TextRaster {
        val factor = minOf(1600f / bitmap.width, 256f / bitmap.height, 1f)
        val image = Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt().coerceAtLeast(1),
            (bitmap.height * factor).toInt().coerceAtLeast(1), true)
        try {
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val gray = IntArray(pixels.size) { index ->
                val pixel = pixels[index]
                (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            }
            val histogram = IntArray(256)
            gray.forEach { histogram[it]++ }
            val totalSum = gray.sum().toDouble()
            var count = 0; var sum = 0.0; var threshold = 127; var best = -1.0
            for (t in 0..254) {
                count += histogram[t]; sum += t * histogram[t]
                if (count == 0 || count == pixels.size) continue
                val difference = sum / count - (totalSum - sum) / (pixels.size - count)
                val variance = count.toDouble() * (pixels.size - count) * difference * difference
                if (variance > best) { best = variance; threshold = t }
            }
            val border = (0 until image.width).sumOf { gray[it] + gray[gray.lastIndex - it] }.toDouble() /
                (2 * image.width)
            val lightBackground = border > threshold
            return TextRaster(image.width, image.height, BooleanArray(gray.size) {
                if (lightBackground) gray[it] <= threshold else gray[it] > threshold
            })
        } finally { if (image !== bitmap) image.recycle() }
    }
}
