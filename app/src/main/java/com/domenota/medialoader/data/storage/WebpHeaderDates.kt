package com.domenota.medialoader.data.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Lossless metadata insertion into private static WebP staging files. */
internal object WebpHeaderDates {
    private data class Chunk(val type: String, val offset: Long, val bytes: Long) {
        val total get() = 8 + bytes + (bytes and 1)
    }
    fun matches(source: File): Boolean = RandomAccessFile(source, "r").use {
        if (it.length() < 12) false else {
            val bytes = ByteArray(12).also(it::readFully)
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        }
    }
    fun copy(source: File, output: File, millis: Long) {
        check(source.canonicalFile != output.canonicalFile) { "Исходник WebP совпал с копией" }
        val chunks = chunks(source)
        check(chunks.none { it.type == "EXIF" }) { "WebP уже содержит EXIF; автоматическая перезапись запрещена" }
        val extended = extended(source, chunks)
        val exif = exif(millis)
        val added = 8L + exif.size + (exif.size and 1) + if (chunks.none { it.type == "VP8X" }) 18 else 0
        check(source.length() - 8 + added <= 0xffffffffL) { "WebP слишком велик для RIFF" }
        RandomAccessFile(source, "r").use { input -> RandomAccessFile(output, "rw").use { out ->
            out.setLength(0)
            out.writeBytes("RIFF"); le32(out, source.length() - 8 + added); out.writeBytes("WEBP")
            out.writeBytes("VP8X"); le32(out, 10); out.write(extended)
            for (chunk in chunks) if (chunk.type != "VP8X") transfer(input, out, chunk.offset, chunk.total)
            out.writeBytes("EXIF"); le32(out, exif.size.toLong()); out.write(exif)
            if (exif.size and 1 != 0) out.write(0)
            out.fd.sync()
        } }
        verify(source, output, millis)
    }
    fun verify(source: File, output: File, millis: Long) {
        val before = chunks(source); val after = chunks(output)
        check(before.none { it.type == "EXIF" } && after.count { it.type == "EXIF" } == 1) { "Неоднозначный EXIF WebP" }
        val header = after.first()
        check(header.type == "VP8X" && header.bytes == 10L &&
            bytes(output, header).contentEquals(extended(source, before))) { "Флаги или размеры WebP изменились" }
        check(bytes(output, after.single { it.type == "EXIF" }).contentEquals(exif(millis))) { "Дата WebP не сохранилась" }
        val oldChunks = before.filter { it.type != "VP8X" }
        val newChunks = after.filter { it.type != "VP8X" && it.type != "EXIF" }
        check(oldChunks.map { it.type to it.bytes } == newChunks.map { it.type to it.bytes } &&
            hash(source, oldChunks).contentEquals(hash(output, newChunks))) {
            "WebP изменил сжатое изображение, ICC-профиль или другие метаданные"
        }
    }
    private fun chunks(source: File): List<Chunk> = RandomAccessFile(source, "r").use { file ->
        check(matches(source)) { "Файл не является WebP" }
        file.seek(4)
        check(u32(file) == file.length() - 8) { "Размер RIFF WebP не совпал" }
        val result = mutableListOf<Chunk>(); var offset = 12L
        while (offset < file.length()) {
            check(file.length() - offset >= 8 && result.size < 100000) { "Обрезанный WebP-блок" }
            file.seek(offset)
            val type = ByteArray(4).also(file::readFully).toString(Charsets.US_ASCII)
            val size = u32(file)
            val chunk = Chunk(type, offset, size)
            check(chunk.total <= file.length() - offset) { "WebP-блок выходит за границы файла" }
            result += chunk; offset += chunk.total
        }
        check(result.count { it.type == "VP8X" } <= 1 && result.count { it.type in setOf("VP8 ", "VP8L") } == 1 &&
            result.none { it.type in setOf("ANIM", "ANMF") }) { "Анимированный или неоднозначный WebP не поддерживается" }
        if (result.any { it.type == "VP8X" }) check(result.first().type == "VP8X") { "Неверный порядок VP8X" }
        result
    }
    private fun extended(source: File, chunks: List<Chunk>): ByteArray {
        val old = chunks.singleOrNull { it.type == "VP8X" }
        if (old != null) {
            check(old.bytes == 10L) { "Неверный размер VP8X" }
            return bytes(source, old).also {
                check(it[0].toInt() and 0xc3 == 0 && it.sliceArray(1..3).all { b -> b == 0.toByte() }) { "Неизвестные флаги WebP" }
                it[0] = (it[0].toInt() or 8).toByte()
            }
        }
        check(chunks.none { it.type in setOf("ICCP", "ALPH", "XMP ") }) { "WebP содержит метаданные без VP8X" }
        val image = chunks.single { it.type in setOf("VP8 ", "VP8L") }
        val prefix = RandomAccessFile(source, "r").use { file ->
            check(image.bytes >= if (image.type == "VP8 ") 10 else 5) { "Обрезан заголовок изображения WebP" }
            file.seek(image.offset + 8); ByteArray(minOf(10L, image.bytes).toInt()).also(file::readFully)
        }
        val width: Int; val height: Int; val alpha: Boolean
        if (image.type == "VP8 ") {
            check(prefix.sliceArray(3..5).contentEquals(byteArrayOf(0x9d.toByte(), 1, 0x2a))) { "Неизвестный кадр VP8" }
            width = ((prefix[6].toInt() and 255) or ((prefix[7].toInt() and 255) shl 8)) and 0x3fff
            height = ((prefix[8].toInt() and 255) or ((prefix[9].toInt() and 255) shl 8)) and 0x3fff
            alpha = false
        } else {
            check(prefix[0] == 0x2f.toByte()) { "Неизвестный кадр VP8L" }
            val bits = ByteBuffer.wrap(prefix, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            check(bits ushr 29 == 0) { "Неизвестная версия VP8L" }
            width = (bits and 0x3fff) + 1; height = ((bits ushr 14) and 0x3fff) + 1
            alpha = bits and (1 shl 28) != 0
        }
        check(width > 0 && height > 0) { "Нулевые размеры WebP" }
        return ByteArray(10).also {
            it[0] = (8 or if (alpha) 16 else 0).toByte()
            for (i in 0..2) { it[4+i] = ((width-1) ushr (8*i)).toByte(); it[7+i] = ((height-1) ushr (8*i)).toByte() }
        }
    }
    private fun exif(millis: Long): ByteArray {
        check(millis in 946684800000L..System.currentTimeMillis()) { "Недопустимая дата WebP" }
        val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val date = (time.format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")) + '\u0000').toByteArray(Charsets.US_ASCII)
        val zone = (time.offset.id.let { if (it == "Z") "+00:00" else it } + '\u0000').toByteArray(Charsets.US_ASCII)
        val out = ByteBuffer.allocate(104 + date.size + zone.size).order(ByteOrder.LITTLE_ENDIAN)
        fun entry(tag: Int, type: Int, count: Int, value: Int) { out.putShort(tag.toShort()); out.putShort(type.toShort()); out.putInt(count); out.putInt(value) }
        out.put('I'.code.toByte()); out.put('I'.code.toByte()); out.putShort(42); out.putInt(8)
        out.putShort(2); entry(0x132, 2, date.size, 104); entry(0x8769, 4, 1, 38); out.putInt(0)
        out.putShort(5)
        entry(0x9003, 2, date.size, 104); entry(0x9004, 2, date.size, 104)
        for (tag in 0x9010..0x9012) entry(tag, 2, zone.size, 104 + date.size)
        out.putInt(0); out.put(date); out.put(zone)
        return out.array()
    }
    private fun bytes(source: File, chunk: Chunk): ByteArray = RandomAccessFile(source, "r").use {
        check(chunk.bytes <= 1024) { "Метаданные WebP превышают ожидаемый размер" }
        it.seek(chunk.offset + 8); ByteArray(chunk.bytes.toInt()).also(it::readFully)
    }
    private fun u32(file: RandomAccessFile) = Integer.reverseBytes(file.readInt()).toLong() and 0xffffffffL
    private fun le32(file: RandomAccessFile, value: Long) = file.writeInt(Integer.reverseBytes(value.toInt()))
    private fun transfer(input: RandomAccessFile, output: RandomAccessFile, offset: Long, bytes: Long) {
        input.seek(offset); var remaining = bytes; val buffer = ByteArray(128*1024)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            check(count > 0) { "WebP обрезан при копировании" }; output.write(buffer, 0, count); remaining -= count
        }
    }
    private fun hash(source: File, chunks: List<Chunk>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(128*1024)
        RandomAccessFile(source, "r").use { file -> for (chunk in chunks) {
            file.seek(chunk.offset); var remaining = chunk.total
            while (remaining > 0) {
                val count = file.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                check(count > 0) { "WebP обрезан при проверке" }; digest.update(buffer, 0, count); remaining -= count
            }
        } }
        return digest.digest()
    }
}
