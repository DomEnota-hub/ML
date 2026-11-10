package com.domenota.medialoader.data.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.AtomicFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.domenota.medialoader.core.database.DownloadDao
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max

/**
 * Isolated ONE-MP4 Xiaomi test. Writes historical container creation_time into
 * a disposable COPY via -c copy (no transcoding). Original, Room history,
 * collections, favorites, and source URL are NEVER modified by this class.
 * A download date is not necessarily the camera's actual recording date.
 */
internal class MediaVideoChronologyTrial(
    private val context: Context,
    private val dao: DownloadDao,
) {
    companion object {
        const val TRIAL_PATH = "Pictures/MediaLoader_QA_VIDEO_Date_Test/"
        private const val MIN_FREE_RESERVE = 192L * 1024 * 1024
    }
    data class Result(val completed: Boolean, val message: String)
    private data class Candidate(val id: String, val uri: String, val name: String,
        val bytes: Long, val historicalMillis: Long)
    private data class VideoProbe(val durationMs: Long, val width: Int, val height: Int,
        val embeddedDate: String?)
    private val resolver get() = context.contentResolver
    private val journal = AtomicFile(File(context.noBackupFilesDir, "ml-qa-video-one-copy.json"))

    private fun readJournal(): JSONObject? = try {
        JSONObject(journal.openRead().bufferedReader().use { it.readText() })
    } catch (_: java.io.FileNotFoundException) { null }

    private fun writeJournal(record: JSONObject) {
        val out = journal.startWrite()
        try {
            out.write(record.toString().toByteArray(Charsets.UTF_8))
            journal.finishWrite(out)
        } catch (error: Throwable) {
            journal.failWrite(out)
            throw error
        }
    }

    private fun candidate(row: DownloadEntity): Candidate? {
        if (Build.VERSION.SDK_INT < 29 || row.state != DownloadState.COMPLETED ||
            row.hidden || row.isPrivate || row.mediaType != MediaType.VIDEO ||
            !MediaDateRepairPolicy.validTime(row.createdAtEpochMillis, System.currentTimeMillis()) ||
            row.savedUri.isNullOrBlank()) return null
        val uri = Uri.parse(row.savedUri)
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return null
        return try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                fun str(col: String): String? = c.getString(c.getColumnIndexOrThrow(col))
                fun num(col: String): Long = c.getLong(c.getColumnIndexOrThrow(col))
                val size = num(MediaStore.MediaColumns.SIZE)
                if (!VideoChronologyTrialPolicy.eligible(
                    str(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), context.packageName,
                    str(MediaStore.MediaColumns.RELATIVE_PATH),
                    str(MediaStore.MediaColumns.DISPLAY_NAME), row.originalName,
                    str(MediaStore.MediaColumns.MIME_TYPE), size,
                    num(MediaStore.MediaColumns.DATE_ADDED) * 1000L,
                    row.createdAtEpochMillis)) return@use null
                Candidate(row.id, row.savedUri!!, row.originalName, size, row.createdAtEpochMillis)
            }
        } catch (_: Exception) { null }
    }

    private fun hash(uri: Uri): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                size += n
            }
        } ?: error("Нет доступа к исходному MP4")
        return digest.digest().joinToString("") { "%02x".format(it) } to size
    }

    private fun hash(file: File): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                size += n
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } to size
    }

    private fun probe(file: File): VideoProbe {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return VideoProbe(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: error("Не удалось прочитать длительность MP4"),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
        } finally { retriever.release() }
    }

    private suspend fun remux(source: File, output: File, timestamp: Long) {
        val instant = Instant.ofEpochMilli(timestamp)
        val dateLocal = instant.atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))
        val args = arrayOf("-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-i", source.absolutePath, "-map", "0", "-map_metadata", "0",
            "-c", "copy", "-metadata", "creation_time=$instant",
            "-metadata", "com.apple.quicktime.creationdate=$dateLocal",
            "-movflags", "+faststart", output.absolutePath)
        val success = suspendCancellableCoroutine<Boolean> { continuation ->
            val session = FFmpegKit.executeWithArgumentsAsync(args) { finished ->
                if (continuation.isActive) continuation.resume(ReturnCode.isSuccess(finished.returnCode))
            }
            continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
        check(success && output.isFile && output.length() > 0L) {
            "MP4 нельзя пересобрать потоковым копированием на устройстве"
        }
        check(output.setLastModified(timestamp)) { "Не удалось выставить mtime временного MP4" }
    }

    suspend fun runTrial(): Result = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 29) { "Для пробы требуется Android 10+" }
        check(readJournal() == null) { "Сначала удалите прошлую видео-пробу" }
        val rows = dao.all()
        check(rows.none { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }) {
            "Дождитесь завершения загрузок"
        }
        val frequencies = rows.mapNotNull { it.savedUri }.groupingBy { it }.eachCount()
        val work = File(context.cacheDir, "qa-video-chronology").apply { mkdirs() }
        var scratchIn: File? = null
        var scratchOut: File? = null
        try {
            val choices = rows.asSequence()
                .filter { it.savedUri != null && frequencies[it.savedUri] == 1 }
                .mapNotNull(::candidate)
                .sortedWith(compareBy<Candidate> { it.bytes }.thenBy { it.historicalMillis })
                .take(30).toList()
            var selected: Candidate? = null
            for (choice in choices) {
                if (dao.countSavedUriReferences(choice.uri) == 1) {
                    selected = choice
                    break
                }
            }
            val source = selected ?: return@withContext Result(false,
                "Не найден подходящий уникальный MP4 ML до 100 МБ. Оригиналы не изменены.")
            val free = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
            check(free > source.bytes * 3 + MIN_FREE_RESERVE) {
                "Недостаточно свободного места для двух безопасных временных MP4"
            }
            val sourceUri = Uri.parse(source.uri)
            val sourceHash = hash(sourceUri)
            check(sourceHash.second == source.bytes) { "Исходный MP4 изменился до начала пробы" }
            scratchIn = File.createTempFile("video-source-", ".mp4", work)
            resolver.openInputStream(sourceUri)?.use { input ->
                scratchIn!!.outputStream().use { input.copyTo(it) }
            } ?: error("MP4 не читается")
            check(hash(scratchIn!!) == sourceHash) { "SHA-256 временного исходника не совпал" }
            val originalProbe = probe(scratchIn!!)
            scratchOut = File.createTempFile("video-dated-", ".mp4", work)
            remux(scratchIn!!, scratchOut!!, source.historicalMillis)
            val outputProbe = probe(scratchOut!!)
            check(outputProbe.durationMs > 0L &&
                abs(outputProbe.durationMs - originalProbe.durationMs) <=
                    max(1000L, originalProbe.durationMs / 100L) &&
                outputProbe.width == originalProbe.width &&
                outputProbe.height == originalProbe.height) {
                "MP4 после remux не прошёл проверку длительности и геометрии"
            }
            check(candidate(dao.byId(source.id) ?: error("Исчезла запись ML")) == source &&
                dao.countSavedUriReferences(source.uri) == 1 && hash(sourceUri) == sourceHash) {
                "Во время подготовки изменился оригинал или история ML"
            }
            val newHash = hash(scratchOut!!)
            val name = "ML_VIDEO_QA_${System.currentTimeMillis()}_HISTORICAL.mp4"
            val entry = JSONObject()
                .put("name", name).put("copyUri", "")
                .put("sourceId", source.id).put("sourceUri", source.uri)
                .put("sourceSha256", sourceHash.first).put("sourceSize", sourceHash.second)
                .put("expectedSha256", newHash.first).put("expectedSize", newHash.second)
                .put("historicalMillis", source.historicalMillis)
            writeJournal(entry) // Reserve unique name before insert; recoverable after process death.
            val trialUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, TRIAL_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: error("MediaStore не создал пробную копию")
            entry.put("copyUri", trialUri.toString())
            writeJournal(entry)
            resolver.openOutputStream(trialUri, "w")?.use { output ->
                scratchOut!!.inputStream().use { it.copyTo(output) }
            } ?: error("MediaStore не разрешил сохранить пробный MP4")
            check(hash(trialUri) == newHash && hash(sourceUri) == sourceHash) {
                "Контрольные суммы тестовой копии или исходника не совпали"
            }
            check(resolver.update(trialUri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 1) { "MediaStore не опубликовал MP4" }
            entry.put("published", true)
            writeJournal(entry)
            val path = resolver.query(trialUri, arrayOf(MediaStore.MediaColumns.DATA),
                null, null, null)?.use { c ->
                if (!c.moveToFirst()) null
                else c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
            }
            path?.let {
                val file = File(it).canonicalFile
                val root = Environment.getExternalStorageDirectory().canonicalPath.trimEnd('/') + "/"
                if (file.path.startsWith(root) && file.path.contains("/" + TRIAL_PATH)) {
                    file.setLastModified(source.historicalMillis)
                    MediaScannerConnection.scanFile(context, arrayOf(file.path),
                        arrayOf("video/mp4"), null)
                }
            }
            delay(1200)
            val dates = resolver.query(trialUri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_ADDED), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null
                else c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)) to
                    (c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)) * 1000L)
            }
            entry.put("indexedTakenMillis", dates?.first ?: 0L)
                .put("indexedAddedMillis", dates?.second ?: 0L)
                .put("embeddedDateBefore", originalProbe.embeddedDate ?: "")
                .put("embeddedDateAfter", outputProbe.embeddedDate ?: "")
            writeJournal(entry)
            val matched = dates?.first?.let { abs(it - source.historicalMillis) <= 60_000L } == true
            Result(true, "Создана ОДНА тестовая MP4-копия: $name. Дата из истории ML: " +
                Instant.ofEpochMilli(source.historicalMillis).atZone(ZoneId.systemDefault()) +
                ". Дата в контейнере: ${outputProbe.embeddedDate ?: "не обнаружена"}. " +
                "MediaStore.DATE_TAKEN: ${if (matched) "совпадает" else "не подтверждена"}; " +
                "DATE_ADDED осталась новой. Проверьте дату, положение и звук в Xiaomi Gallery, " +
                "затем удалите копию. Оригинал и история не менялись.")
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            Result(false, "Видеопроба остановлена: ${error.message ?: "ошибка"}. " +
                "Исходник и Room не изменены. Если копия создана, удалите её в лаборатории.")
        } finally {
            scratchIn?.delete()
            scratchOut?.delete()
        }
    }

    suspend fun removeTrial(): Result = withContext(Dispatchers.IO) {
        val record = readJournal() ?: return@withContext Result(true, "Пробного MP4 нет")
        val name = record.getString("name")
        val storedUri = record.optString("copyUri")
        val uri = if (storedUri.isNotBlank()) Uri.parse(storedUri) else {
            resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(TRIAL_PATH, name), null)?.use { c ->
                if (!c.moveToFirst() || c.count != 1) null else
                    Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)).toString())
            }
        } ?: return@withContext Result(false, "Не удалось однозначно найти тестовую MP4-копию")
        val valid = try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use { c ->
                c.moveToFirst() &&
                    c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)) == context.packageName &&
                    c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)) == TRIAL_PATH &&
                    c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) == name &&
                    (c.getInt(c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)) == 1 ||
                        hash(uri) == (record.getString("expectedSha256") to record.getLong("expectedSize")))
            } ?: false
        } catch (_: Exception) { false }
        if (!valid) return@withContext Result(false,
            "Неизвестная или изменившаяся копия: безопасное удаление заблокировано")
        if (resolver.delete(uri, null, null) != 1) return@withContext Result(false,
            "Android не разрешил удаление тестового MP4")
        journal.delete()
        Result(true, "Пробная MP4-копия удалена. Оригинал и ссылки в ML сохранены.")
    }
}

/** Production preflight must be stricter: trial is permitted to overwrite dates on COPY only. */
internal object VideoChronologyTrialPolicy {
    const val MAX_TEST_VIDEO_BYTES = 100L * 1024 * 1024
    fun eligible(owner: String?, ownPackage: String, relativePath: String?,
        mediaName: String?, originalName: String, mime: String?, size: Long,
        addedMillis: Long, historicalMillis: Long): Boolean =
        owner == ownPackage && relativePath == "Pictures/MediaLoader/" &&
        mediaName != null && mediaName == originalName &&
        mediaName.endsWith(".mp4", ignoreCase = true) && mime == "video/mp4" &&
        size in 1L..MAX_TEST_VIDEO_BYTES &&
        historicalMillis in 946684800000L..System.currentTimeMillis() &&
        addedMillis > historicalMillis + 86_400_000L
}
