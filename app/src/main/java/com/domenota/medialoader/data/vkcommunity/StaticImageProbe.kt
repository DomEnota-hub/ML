package com.domenota.medialoader.data.vkcommunity

import java.io.File
import java.io.RandomAccessFile

/** Checks the actual container, including APNG/WebP animation chunks. */
object StaticImageProbe {
    enum class Format(val extension: String, val mime: String) {
        JPEG("jpg", "image/jpeg"), PNG("png", "image/png"), WEBP("webp", "image/webp"),
    }

    fun detect(file: File): Format? {
        if (!file.isFile || file.length() < 4) return null
        return runCatching { RandomAccessFile(file, "r").use(::detect) }.getOrNull()
    }

    private fun detect(input: RandomAccessFile): Format? {
        val length = input.length()
        val first = ByteArray(minOf(length, 16).toInt())
        input.readFully(first)
        if (first[0] == 0xff.toByte() && first[1] == 0xd8.toByte() &&
            first[2] == 0xff.toByte()) return Format.JPEG

        val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        if (first.size >= 8 && first.copyOfRange(0, 8).contentEquals(png)) {
            var cursor = 8L
            while (cursor + 12 <= length) {
                input.seek(cursor)
                val size = input.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                val next = cursor + 12 + size
                if (next > length) return null
                if (type == "acTL" || type == "fcTL" || type == "fdAT") return null
                if (type == "IDAT" || type == "IEND") return Format.PNG
                cursor = next
            }
            return null
        }

        if (first.size < 12 || !first.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) ||
            !first.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())) return null
        // WebP RIFF chunks are little-endian and padded to even byte boundaries.
        val riffSize = littleInt(first, 4).toLong() and 0xffffffffL
        if (riffSize + 8 > length || riffSize < 12) return null
        var cursor = 12L
        var imageChunk = false
        while (cursor + 8 <= minOf(length, riffSize + 8)) {
            input.seek(cursor)
            val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            val sizeBytes = ByteArray(4).also(input::readFully)
            val size = littleInt(sizeBytes, 0).toLong() and 0xffffffffL
            val next = cursor + 8 + size + (size and 1L)
            if (next > length || next > riffSize + 8) return null
            if (type == "ANIM" || type == "ANMF") return null
            if (type == "VP8X") {
                if (size < 1) return null
                val flags = input.readUnsignedByte()
                if (flags and 0x02 != 0) return null
                imageChunk = true
            }
            if (type == "VP8 " || type == "VP8L") imageChunk = true
            cursor = next
        }
        return if (imageChunk) Format.WEBP else null
    }

    private fun littleInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) or
            ((bytes[offset + 2].toInt() and 255) shl 16) or
            ((bytes[offset + 3].toInt() and 255) shl 24)
}
