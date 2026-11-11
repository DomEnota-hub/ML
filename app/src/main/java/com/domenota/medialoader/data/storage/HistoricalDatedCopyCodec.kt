package com.domenota.medialoader.data.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Build
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.ResolverStyle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

/** Public-tag equivalent of AndroidX's restricted EXIF timestamp getters. */
internal fun readHistoricalExifTimestamp(exif: androidx.exifinterface.media.ExifInterface): Long? {
    fun read(dateTag: String, subsecTag: String, offsetTag: String): Long? =
        parseHistoricalExifTimestamp(
            exif.getAttribute(dateTag), exif.getAttribute(subsecTag), exif.getAttribute(offsetTag))
    return read(
        androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL,
        androidx.exifinterface.media.ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ) ?: read(
        androidx.exifinterface.media.ExifInterface.TAG_DATETIME,
        androidx.exifinterface.media.ExifInterface.TAG_SUBSEC_TIME,
        androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME,
    )
}

/**
 * EXIF timestamps are wall-clock strings, commonly without timezone.
 * Match AndroidX ExifInterface: absent offset is treated as UTC, explicit
 * offsets shift the instant, and up to three subsecond digits are retained.
 * Invalid, partial or ambiguous values return null rather than inventing dates.
 */
internal fun parseHistoricalExifTimestamp(
    date: String?, subsec: String?, offset: String?,
): Long? {
    if (date.isNullOrBlank() || date.startsWith("0000")) return null
    val instant = runCatching {
        val formats = listOf("uuuu:MM:dd HH:mm:ss", "uuuu-MM-dd HH:mm:ss")
        val local = formats.firstNotNullOfOrNull { pattern ->
            runCatching {
                LocalDateTime.parse(date, DateTimeFormatter.ofPattern(pattern)
                    .withResolverStyle(ResolverStyle.STRICT))
            }.getOrNull()
        } ?: return null
        val zone = if (offset.isNullOrBlank()) ZoneOffset.UTC else {
            require(Regex("[+-]\\d{2}:\\d{2}").matches(offset))
            ZoneOffset.of(offset)
        }
        val millis = local.toInstant(zone).toEpochMilli()
        val fractionalMillis = if (subsec.isNullOrBlank()) 0L else {
            require(subsec.all(Char::isDigit))
            subsec.take(3).padEnd(3, '0').toLong()
        }
        millis + fractionalMillis
    }.getOrNull()
    return instant?.takeIf { it > 0 }
}

/** Operates exclusively on caller-owned scratch files. Never resolves or changes a media URI. */
internal class HistoricalDatedCopyCodec {
    /** Existing capture metadata determined the file's original gallery position. */
    fun existingDate(source: File, mime: String): Long? {
        if (mime.startsWith("image/")) {
            if (Build.VERSION.SDK_INT < 31) {
                // AndroidX's dateTimeOriginal/dateTime accessors are RestrictedApi.
                // Read public EXIF tags instead; match their UTC-like interpretation
                // and honor the explicit offset/subseconds when present.
                val compatible = androidx.exifinterface.media.ExifInterface(source.path)
                return readHistoricalExifTimestamp(compatible)
            }
            val exif = ExifInterface(source.path)
            if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null &&
                exif.getAttribute(ExifInterface.TAG_DATETIME) == null) return null
            // Use the platform's interpretation, including absent offsets and
            // subsecond tags, exactly as the MediaStore scanner does.
            val date = exif.getDateTimeOriginal().takeIf { it > 0 } ?: exif.getDateTime().takeIf { it > 0 }
            return requireNotNull(date) { "Встроенная дата фотографии не читается" }
        }
        if (mime == "video/mp4") {
            val raw = probe(source).getJSONObject("format").optJSONObject("tags")?.optString("creation_time").orEmpty()
            return raw.takeIf { it.isNotBlank() }?.let { Instant.parse(it).toEpochMilli() }
        }
        return null
    }
    fun contentMime(source: File, declared: String): String =
        if (declared == "image/jpeg" && WebpHeaderDates.matches(source)) "image/webp" else declared

    suspend fun build(source: File, output: File, mime: String, epochMillis: Long): Unit =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            distinct(source, output)
            check(epochMillis in 946684800000L..System.currentTimeMillis()) {
                "Дата из истории недопустима для восстановления"
            }
            when (contentMime(source, mime)) {
                "image/webp" -> {
                    WebpHeaderDates.copy(source, output, epochMillis)
                    val expected = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                    check(ExifInterface(output.absolutePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == expected) {
                        "Android не прочитал историческую EXIF-дату WebP"
                    }
                }
                "image/jpeg" -> {
                    val before = ExifInterface(source.absolutePath)
                    check(listOf(ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
                        ExifInterface.TAG_DATETIME_DIGITIZED).all { before.getAttribute(it).isNullOrBlank() }) {
                        "У фотографии уже есть EXIF-дата: автоматическая замена запрещена"
                    }
                    jpegSignature(source) // Reject unsupported EXIF before creating a replacement.
                    source.inputStream().use { input -> output.outputStream().use { input.copyTo(it) } }
                    val local = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
                    val date = local.format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                    val offset = local.offset.id.let { if (it == "Z") "+00:00" else it }
                    val exif = ExifInterface(output.absolutePath)
                    listOf(ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
                        ExifInterface.TAG_DATETIME_DIGITIZED).forEach { exif.setAttribute(it, date) }
                    if (Build.VERSION.SDK_INT >= 30) {
                        listOf(ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                            ExifInterface.TAG_OFFSET_TIME_DIGITIZED).forEach { exif.setAttribute(it, offset) }
                    }
                    exif.saveAttributes()
                    val saved = ExifInterface(output.absolutePath)
                    check(listOf(ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
                        ExifInterface.TAG_DATETIME_DIGITIZED).all { saved.getAttribute(it) == date }) {
                        "Не удалось сохранить EXIF-дату временной фотографии"
                    }
                    if (Build.VERSION.SDK_INT >= 30) check(listOf(ExifInterface.TAG_OFFSET_TIME,
                        ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED)
                        .all { saved.getAttribute(it) == offset }) {
                        "Не удалось сохранить часовой пояс EXIF"
                    }
                }
                "video/mp4" -> {
                    videoSignature(source)
                    val instant = Instant.ofEpochMilli(epochMillis)
                    val local = instant.atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))
                    ffmpeg(arrayOf("-i", source.absolutePath, "-map", "0", "-map_metadata", "0",
                        "-c", "copy", "-metadata", "creation_time=$instant", "-metadata",
                        "com.apple.quicktime.creationdate=$local", "-movflags", "+faststart",
                        "-f", "mp4", output.absolutePath))
                    if (videoSignature(source) != videoSignature(output)) {
                        // Some MP4 muxers round the last AAC packet/edit-list duration.
                        // Keep the existing strict signature and payload checks: fall
                        // back to a same-size private copy changing ONLY header dates.
                        // Reject competing metadata dates that could override mvhd.
                        val original = probe(source)
                        val tagSets = listOfNotNull(original.optJSONObject("format")?.optJSONObject("tags")) +
                            (0 until original.getJSONArray("streams").length()).mapNotNull {
                                original.getJSONArray("streams").getJSONObject(it).optJSONObject("tags")
                            }
                        check(tagSets.none { tags -> tags.keys().asSequence().any { key ->
                            key != "creation_time" && (key.contains("date", true) || key.contains("time", true))
                        } }) { "MP4 содержит дополнительные поля даты; точное восстановление заголовков неоднозначно" }
                        Mp4HeaderDates.copy(source, output, epochMillis)
                    }
                    val metadata = probe(output).getJSONObject("format").optJSONObject("tags")
                    val actual = metadata?.optString("creation_time")?.let {
                        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
                    }
                    check(actual != null && abs(actual - epochMillis) < 1000L) {
                        "MP4 не сохранил дату создания из истории"
                    }
                }
                else -> error("Восстановление даты поддерживается только для JPEG и MP4")
            }
            currentCoroutineContext().ensureActive()
            check(output.isFile && output.length() > 0L && output.setLastModified(epochMillis)) {
                "Не удалось сохранить временную копию с исторической датой файла"
            }
        }

    suspend fun verify(source: File, output: File, mime: String): Unit = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        distinct(source, output)
        check(output.isFile && output.length() > 0L) { "Временная копия отсутствует или пуста" }
        when (contentMime(source, mime)) {
            "image/webp" -> {
                WebpHeaderDates.verify(source, output, output.lastModified())
                check(pixelHash(source) == pixelHash(output)) { "Декодированные пиксели WebP не совпали с исходником" }
            }
            "image/jpeg" -> {
                check(sameJpegMetadata(source, output)) {
                    "JPEG изменил изображение или метаданные вне разрешённых полей даты"
                }
                check(pixelHash(source) == pixelHash(output)) {
                    "Декодированные пиксели JPEG не совпали с исходником"
                }
            }
            "video/mp4" -> {
                check(videoSignature(source) == videoSignature(output)) {
                    "MP4 изменил свойства потоков, звук, поворот или длительность"
                }
                check(packetHashes(source) == packetHashes(output)) {
                    "Контрольные суммы сжатых потоков MP4 не совпали с исходником"
                }
            }
            else -> error("Проверка даты поддерживается только для JPEG и MP4")
        }
    }

    private fun sameJpegMetadata(source: File, output: File): Boolean {
        val before = jpegSignature(source)
        val after = jpegSignature(output)
        if (before.first != after.first || before.second.any { after.second[it.key] != it.value }) return false
        // The framework materializes these default/derived fields even when the input has no EXIF.
        // Admit only newly added defaults with the exact same effective value on both files.
        val defaults = mapOf("ifd0/256" to ExifInterface.TAG_IMAGE_WIDTH,
            "ifd0/257" to ExifInterface.TAG_IMAGE_LENGTH, "ifd0/274" to ExifInterface.TAG_ORIENTATION,
            "ifd0/34665/37384" to ExifInterface.TAG_LIGHT_SOURCE)
        val oldExif = ExifInterface(source.absolutePath)
        val newExif = ExifInterface(output.absolutePath)
        return after.second.keys.filter { it !in before.second }.all { key ->
            val attribute = defaults[key] ?: return@all false
            oldExif.getAttribute(attribute) == newExif.getAttribute(attribute)
        }
    }

    private fun distinct(source: File, output: File) {
        check(source.canonicalFile != output.canonicalFile) { "Исходник и временная копия должны быть разными файлами" }
        check(source.isFile && source.length() > 0L) { "Временный исходник отсутствует или пуст" }
    }

    private suspend fun ffmpeg(arguments: Array<String>) {
        currentCoroutineContext().ensureActive()
        // Unparented completion survives caller cancellation until the native writer has stopped.
        val completion = CompletableDeferred<Boolean>()
        val session = FFmpegKit.executeWithArgumentsAsync(arrayOf("-nostdin", "-hide_banner",
            "-loglevel", "error", "-y") + arguments) { completed ->
            completion.complete(ReturnCode.isSuccess(completed.returnCode))
        }
        val ok = try {
            completion.await()
        } catch (cancel: CancellationException) {
            try {
                FFmpegKit.cancel(session.sessionId)
            } finally {
                withContext(NonCancellable) { completion.await() }
            }
            throw cancel
        }
        check(ok) { "FFmpeg не смог обработать все потоки без перекодирования; формат не поддерживается" }
    }

    private fun probe(file: File): JSONObject {
        val session = FFprobeKit.executeWithArguments(arrayOf("-v", "error", "-show_streams",
            "-show_format", "-of", "json", file.absolutePath))
        check(ReturnCode.isSuccess(session.returnCode)) { "Не удалось проверить структуру MP4" }
        return JSONObject(session.output ?: error("FFprobe не вернул структуру MP4"))
    }

    private fun videoSignature(file: File): List<String> {
        val json = probe(file)
        val format = json.getJSONObject("format")
        check(format.optString("format_name").split(',').any { it == "mp4" || it == "mov" }) {
            "Исходный файл не является поддерживаемым MP4"
        }
        val streams = json.getJSONArray("streams")
        check(streams.length() > 0) { "MP4 не содержит потоков" }
        val result = mutableListOf(format.optString("duration"))
        var hasVideo = false
        for (index in 0 until streams.length()) {
            val stream = streams.getJSONObject(index)
            val type = stream.optString("codec_type")
            check(type in setOf("video", "audio", "subtitle", "data")) { "MP4 содержит неподдерживаемый тип потока: $type" }
            hasVideo = hasVideo || type == "video"
            val keys = listOf("codec_type", "codec_name", "codec_tag_string", "profile", "level",
                "width", "height", "pix_fmt", "sample_aspect_ratio", "display_aspect_ratio",
                "sample_rate", "channels", "channel_layout", "duration", "nb_frames",
                "color_range", "color_space", "color_transfer", "color_primaries")
            result += keys.joinToString("|") { "$it=${stream.optString(it)}" }
            result += stream.optJSONObject("disposition")?.toString().orEmpty()
            // Display matrix includes rotation and mirroring; never compare only width/height.
            result += stream.optJSONArray("side_data_list")?.toString().orEmpty()
            result += stream.optJSONObject("tags")?.optString("rotate").orEmpty()
        }
        check(hasVideo) { "В MP4 отсутствует видеопоток" }
        return result
    }

    private suspend fun packetHashes(file: File): String {
        val report = File.createTempFile("historical-packets-", ".txt", file.parentFile)
        try {
            ffmpeg(arrayOf("-i", file.absolutePath, "-map", "0", "-c", "copy", "-f",
                "streamhash", "-hash", "sha256", report.absolutePath))
            check(report.length() in 1..(1024L * 1024)) { "Не получены контрольные суммы всех потоков MP4" }
            return report.readText().lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }
                .joinToString("\n").also { check(it.isNotEmpty()) { "Проверка потоков MP4 вернула пустой результат" } }
        } finally { report.delete() }
    }

    @Suppress("DEPRECATION")
    private suspend fun pixelHash(file: File): String {
        val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false)
            ?: error("Не удалось открыть JPEG для проверки пикселей")
        try {
            check(decoder.width in 1..65535 && decoder.height in 1..65535) { "Размер JPEG не поддерживается" }
            val digest = MessageDigest.getInstance("SHA-256")
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
            // At most 256K pixels per region, regardless of full image dimensions.
            val rows = maxOf(1, minOf(64, 262144 / decoder.width))
            for (top in 0 until decoder.height step rows) {
                currentCoroutineContext().ensureActive()
                val height = minOf(rows, decoder.height - top)
                val bitmap = decoder.decodeRegion(Rect(0, top, decoder.width, top + height), options)
                    ?: error("Не удалось декодировать полосу JPEG")
                try {
                    val pixels = IntArray(decoder.width * height)
                    bitmap.getPixels(pixels, 0, decoder.width, 0, 0, decoder.width, height)
                    val bytes = ByteBuffer.allocate(pixels.size * 4)
                    bytes.asIntBuffer().put(pixels)
                    digest.update(bytes.array())
                } finally { bitmap.recycle() }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally { decoder.recycle() }
    }

    /** Hash every JPEG byte except EXIF, then compare every TIFF value except date/offset fields. */
    private fun jpegSignature(file: File): Pair<String, Map<String, String>> {
        val digest = MessageDigest.getInstance("SHA-256")
        val tags = sortedMapOf<String, String>()
        RandomAccessFile(file, "r").use { input ->
            check(input.readUnsignedShort() == 0xffd8) { "Файл не является JPEG" }
            digest.update(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
            var exifSeen = false
            while (true) {
                check(input.readUnsignedByte() == 0xff) { "Неподдерживаемая структура JPEG" }
                val marker = input.readUnsignedByte()
                check(marker != 0xff && marker != 0 && marker != 0xd8) { "Неподдерживаемый маркер JPEG" }
                if (marker == 0xd9) { digest.update(byteArrayOf(0xff.toByte(), marker.toByte())); break }
                val length = input.readUnsignedShort()
                check(length >= 2) { "Повреждённый сегмент JPEG" }
                val payload = ByteArray(length - 2).also { input.readFully(it) }
                if (marker == 0xe1 && payload.size >= 6 && payload.copyOfRange(0, 6)
                        .contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))) {
                    check(!exifSeen) { "Несколько EXIF-блоков JPEG не поддерживаются" }
                    exifSeen = true
                    tags.putAll(tiffValues(payload.copyOfRange(6, payload.size)))
                } else {
                    digest.update(byteArrayOf(0xff.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()))
                    digest.update(payload)
                }
                if (marker == 0xda) {
                    val buffer = ByteArray(65536)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                    break
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } to tags
    }

    private fun tiffValues(data: ByteArray): Map<String, String> {
        fun unsupported(): Nothing = error("Структура EXIF не поддерживается для безопасного восстановления даты")
        if (data.size < 8) unsupported()
        val order = when (String(data, 0, 2, Charsets.US_ASCII)) { "II" -> ByteOrder.LITTLE_ENDIAN; "MM" -> ByteOrder.BIG_ENDIAN; else -> unsupported() }
        val buffer = ByteBuffer.wrap(data).order(order)
        fun u16(at: Int): Int { if (at < 0 || at.toLong() + 2 > data.size) unsupported(); return buffer.getShort(at).toInt() and 65535 }
        fun u32(at: Int): Int { if (at < 0 || at.toLong() + 4 > data.size) unsupported(); val value = buffer.getInt(at).toLong() and 0xffffffffL; if (value > Int.MAX_VALUE) unsupported(); return value.toInt() }
        if (u16(2) != 42) unsupported()
        val result = sortedMapOf<String, String>()
        val visited = mutableSetOf<Int>()
        val dates = setOf(0x132, 0x9003, 0x9004, 0x9010, 0x9011, 0x9012)
        fun walk(offset: Int, path: String) {
            if (offset == 0) return
            if (!visited.add(offset) || visited.size > 16) unsupported()
            val count = u16(offset)
            if (count > 1024 || offset.toLong() + 2 + count * 12L + 4 > data.size) unsupported()
            var thumbOffset = 0; var thumbSize = 0
            for (index in 0 until count) {
                val at = offset + 2 + index * 12
                val tag = u16(at); val type = u16(at + 2); val amount = u32(at + 4)
                val unit = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12 -> 8; else -> unsupported() }
                val size = amount.toLong() * unit
                if (size > data.size) unsupported()
                val start = if (size <= 4) at + 8 else u32(at + 8)
                if (start.toLong() + size > data.size) unsupported()
                if (tag in setOf(0x8769, 0x8825, 0xa005)) {
                    if (type != 4 || amount != 1) unsupported()
                    walk(u32(at + 8), "$path/$tag")
                } else if (tag == 0x201 || tag == 0x202) {
                    if (type != 4 || amount != 1) unsupported()
                    if (tag == 0x201) thumbOffset = u32(at + 8) else thumbSize = u32(at + 8)
                } else if (tag !in dates) {
                    // Maker notes may embed offsets relative to the old IFD; strip/tile images need relocation.
                    if (tag in setOf(0x927c, 0x111, 0x144, 0x14a)) unsupported()
                    val key = "$path/$tag"
                    if (result.containsKey(key)) unsupported()
                    result[key] = "$type:$amount:" + data.copyOfRange(start, start + size.toInt())
                        .joinToString("") { "%02x".format(it) }
                }
            }
            if (thumbOffset != 0 || thumbSize != 0) {
                if (thumbOffset == 0 || thumbSize == 0 || thumbOffset.toLong() + thumbSize > data.size) unsupported()
                result["$path/thumbnail"] = MessageDigest.getInstance("SHA-256")
                    .digest(data.copyOfRange(thumbOffset, thumbOffset + thumbSize)).joinToString("") { "%02x".format(it) }
            }
            walk(u32(offset + 2 + count * 12), "$path/next")
        }
        walk(u32(4), "ifd0")
        return result
    }
}
