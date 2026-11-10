package com.domenota.medialoader.data.storage

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Private staging files only. No muxing: offsets, edit lists and every media byte stay intact. */
internal object Mp4HeaderDates {
    private data class Field(val offset: Long, val width: Int)
    private const val MP4_EPOCH = 2082844800L

    fun copy(source: File, output: File, millis: Long) {
        check(source.canonicalFile != output.canonicalFile) { "Исходник MP4 совпал с копией" }
        check(millis >= 946684800000L && millis <= System.currentTimeMillis()) { "Недопустимая дата MP4" }
        val fields = fields(source)
        val seconds = millis / 1000 + MP4_EPOCH
        check(fields.all { it.width == 8 || seconds <= 0xffffffffL }) { "Дата не помещается в заголовок MP4" }
        source.inputStream().use { input -> output.outputStream().use { input.copyTo(it) } }
        RandomAccessFile(output, "rw").use { file ->
            for (field in fields) {
                file.seek(field.offset)
                if (field.width == 4) file.writeInt(seconds.toInt()) else file.writeLong(seconds)
            }
            file.fd.sync()
        }
        verify(source, output, millis)
    }

    fun verify(source: File, output: File, millis: Long) {
        val before = fields(source)
        check(source.length() == output.length() && before == fields(output)) { "Структура копии MP4 изменилась" }
        val seconds = millis / 1000 + MP4_EPOCH
        RandomAccessFile(output, "r").use { file ->
            for (field in before) {
                file.seek(field.offset)
                val actual = if (field.width == 4) file.readInt().toLong() and 0xffffffffL else file.readLong()
                check(actual == seconds) { "Дата заголовка MP4 не сохранилась" }
            }
        }
        check(contentHash(source, before).contentEquals(contentHash(output, before))) {
            "MP4 изменился вне полей дат: потоки, длительность или служебная структура"
        }
    }

    private fun fields(source: File): List<Field> = RandomAccessFile(source, "r").use { file ->
        val fields = mutableListOf<Field>()
        var boxes = 0
        var movies = 0
        var movieHeaders = 0
        var tracks = 0
        var trackHeaders = 0
        var mediaHeaders = 0
        fun walk(start: Long, end: Long, parent: String) {
            var position = start
            var localHeaders = 0
            var mediaContainers = 0
            while (position < end) {
                check(end - position >= 8 && ++boxes <= 100000) { "Повреждённый или неподдерживаемый MP4-блок" }
                file.seek(position)
                val shortSize = file.readInt().toLong() and 0xffffffffL
                val typeBytes = ByteArray(4).also { file.readFully(it) }
                val type = String(typeBytes, Charsets.ISO_8859_1)
                val header = if (shortSize == 1L) 16L else 8L
                check(end - position >= header) { "Обрезан заголовок MP4" }
                val size = when (shortSize) { 0L -> end - position; 1L -> file.readLong(); else -> shortSize }
                check(size >= header && size <= end - position) { "Недопустимый размер MP4-блока" }
                val payload = position + header
                val finish = position + size
                when {
                    parent == "root" && type == "moov" -> { movies++; walk(payload, finish, "moov") }
                    parent == "moov" && type == "trak" -> { tracks++; walk(payload, finish, "trak") }
                    parent == "trak" && type == "mdia" -> { mediaContainers++; walk(payload, finish, "mdia") }
                    type == "cmov" -> error("Сжатые заголовки MP4 не поддерживаются")
                    type == "mvhd" && parent == "moov" || type == "tkhd" && parent == "trak" ||
                        type == "mdhd" && parent == "mdia" -> {
                        check(finish - payload >= 4) { "Обрезано поле версии MP4" }
                        file.seek(payload)
                        val width = when (file.readUnsignedByte()) {
                            0 -> 4
                            1 -> 8
                            else -> error("Неизвестная версия заголовка даты MP4")
                        }
                        val minimum = when (type) {
                            "mvhd" -> if (width == 4) 100 else 112
                            "tkhd" -> if (width == 4) 84 else 96
                            else -> if (width == 4) 24 else 36
                        }
                        check(finish - payload >= minimum) { "Обрезан заголовок даты MP4" }
                        fields += Field(payload + 4, width)
                        fields += Field(payload + 4 + width, width)
                        localHeaders++
                        when (type) { "mvhd" -> movieHeaders++; "tkhd" -> trackHeaders++; else -> mediaHeaders++ }
                    }
                }
                position = finish
            }
            if (parent in setOf("moov", "trak", "mdia"))
                check(localHeaders == 1) { "Неоднозначный или отсутствующий заголовок даты MP4" }
            if (parent == "trak") check(mediaContainers == 1) { "Неоднозначная структура дорожки MP4" }
        }
        check(file.length() >= 8) { "MP4 пуст или обрезан" }
        walk(0, file.length(), "root")
        check(movies == 1 && movieHeaders == 1 && tracks > 0 &&
            trackHeaders == tracks && mediaHeaders == tracks) { "Неоднозначные заголовки даты MP4" }
        fields.sortedBy { it.offset }
    }

    /** Hash all bytes except the explicitly parsed creation/modification fields. */
    private fun contentHash(source: File, fields: List<Field>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        RandomAccessFile(source, "r").use { input ->
            for (field in fields) {
                var remaining = field.offset - input.filePointer
                check(remaining >= 0) { "Поля даты MP4 пересекаются" }
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    check(count > 0) { "MP4 обрезан при проверке" }
                    digest.update(buffer, 0, count); remaining -= count
                }
                input.seek(field.offset + field.width)
            }
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }
}
