package com.domenota.medialoader.data.storage

import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
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
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * QA ONLY. Existing-uri experiment on a *disposable*, app-owned MediaStore copy.
 * The original downloaded item is opened READ-ONLY. The existing Room savedUri,
 * ids, gallery catalogs, source links, private items and actual downloads are
 * never mutated. This class has NO loop capable of modifying user media.
 *
 * The source row's createdAtEpochMillis currently means "queued in ML history";
 * it isn't proven to be the exact instant download bytes finished saving.
 */
internal class MediaInPlaceChronologyTrial(private val context: Context, private val dao: DownloadDao) {
    enum class Kind(val extension: String, val mime: String, val label: String) {
        JPEG("jpg", "image/jpeg", "JPEG"), MP4("mp4", "video/mp4", "MP4")
    }
    companion object {
        const val TRIAL_PATH = "Pictures/MediaLoader_QA_SameUri_Test/"
        private const val RESERVE = 128L * 1024L * 1024L
    }
    data class Result(val completed: Boolean, val message: String)
    private data class Candidate(val row: DownloadEntity, val sourceUri: Uri, val size: Long)
    private data class Item(val owner: String?, val path: String?, val name: String?,
        val mime: String?, val size: Long, val id: Long, val taken: Long, val added: Long,
        val modified: Long,
        val data: String?)
    private val resolver get() = context.contentResolver
    private fun journal(k: Kind) = AtomicFile(File(context.noBackupFilesDir,
        "ml-qa-same-uri-${k.extension}.json"))
    private fun backup(k: Kind) = File(context.noBackupFilesDir,
        "ml-qa-same-uri-${k.extension}-backup.${k.extension}")
    private fun journalRead(k: Kind): JSONObject? = try {
        JSONObject(journal(k).openRead().bufferedReader().use { it.readText() })
    } catch (_: java.io.FileNotFoundException) { null }
    private fun journalWrite(k: Kind, item: JSONObject) {
        val file = journal(k)
        val stream = file.startWrite()
        try { stream.write(item.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }

    private fun mediaKind(k: Kind): Uri = when (k) {
        Kind.JPEG -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        Kind.MP4 -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
    private fun inspect(uri: Uri): Item? = resolver.query(uri, arrayOf(
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATA,
    ), null, null, null)?.use { c ->
        if (!c.moveToFirst()) null else {
            fun str(name: String) = c.getString(c.getColumnIndexOrThrow(name))
            fun num(name: String) = if (c.isNull(c.getColumnIndexOrThrow(name))) 0L
                else c.getLong(c.getColumnIndexOrThrow(name))
            Item(str(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                str(MediaStore.MediaColumns.RELATIVE_PATH),
                str(MediaStore.MediaColumns.DISPLAY_NAME),
                str(MediaStore.MediaColumns.MIME_TYPE),
                num(MediaStore.MediaColumns.SIZE), num(MediaStore.MediaColumns._ID),
                num(MediaStore.MediaColumns.DATE_TAKEN),
                num(MediaStore.MediaColumns.DATE_ADDED) * 1000L,
                num(MediaStore.MediaColumns.DATE_MODIFIED) * 1000L,
                str(MediaStore.MediaColumns.DATA))
        }
    }
    private fun checksum(uri: Uri): Pair<String, Long> {
        val stream = resolver.openInputStream(uri) ?: error("Невозможно прочитать MediaStore URI")
        return checksum(stream)
    }
    private fun checksum(file: File): Pair<String, Long> = file.inputStream().use { checksum(it) }
    private fun checksum(stream: java.io.InputStream): Pair<String, Long> = stream.use {
        val md = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        val buffer = ByteArray(65536)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            md.update(buffer, 0, count)
            bytes += count
        }
        md.digest().joinToString("") { b -> "%02x".format(b) } to bytes
    }
    private fun validCandidate(row: DownloadEntity, k: Kind): Candidate? {
        if (row.state != DownloadState.COMPLETED || row.hidden || row.isPrivate ||
            row.savedUri.isNullOrBlank() || row.mediaType != when (k) {
                Kind.JPEG -> MediaType.PHOTO
                Kind.MP4 -> MediaType.VIDEO
            } || !MediaDateRepairPolicy.validTime(row.createdAtEpochMillis,
                System.currentTimeMillis())) return null
        val uri = Uri.parse(row.savedUri)
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return null
        val item = try { inspect(uri) } catch (_: Exception) { null } ?: return null
        val eligible = when (k) {
            Kind.JPEG -> ExifTrialPolicy.eligible(item.owner, context.packageName,
                item.path, item.name, row.originalName, item.mime,
                item.size, item.added, row.createdAtEpochMillis)
            Kind.MP4 -> VideoChronologyTrialPolicy.eligible(item.owner, context.packageName,
                item.path, item.name, row.originalName, item.mime,
                item.size, item.added, row.createdAtEpochMillis)
        }
        return if (eligible && item.id > 0 && item.size > 0L &&
            abs(item.taken - row.createdAtEpochMillis) > 60_000L)
            Candidate(row, uri, item.size) else null
    }
    private fun assertTrialIdentity(uri: Uri, expectedName: String, expectedId: Long,
        k: Kind): Item {
        check(uri.authority == MediaStore.AUTHORITY && uri.scheme == "content" &&
            uri.pathSegments.lastOrNull()?.toLongOrNull() == expectedId) {
            "URI пробной копии отличается от записанного в журнал"
        }
        val row = inspect(uri) ?: error("MediaStore потерял тестовую копию")
        check(InPlaceTrialPolicy.ownedTrial(row.owner, context.packageName,
            row.path, row.name, expectedName, row.mime, k.mime, row.id, expectedId) &&
            row.size >= 0) { "Нарушена идентичность тестового файла" }
        return row
    }
    private fun assertOnlyOneTrial(row: Item, k: Kind) {
        val count = resolver.query(mediaKind(k), arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(TRIAL_PATH, row.name ?: ""), null)?.use { it.count } ?: 0
        check(count == 1) { "Android создал дубликаты: сохранение URI не подтверждено" }
    }
    private fun writeSameUri(uri: Uri, temp: File) {
        // CRITICAL: caller has verified this is our private QA copy, never a savedUri.
        // "rwt" explicitly truncates the file through the SAME MediaStore row.
        val descriptor = resolver.openFileDescriptor(uri, "rwt")
            ?: error("Android запретил изменение существующей записи MediaStore")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            temp.inputStream().use { input -> input.copyTo(output) }
            output.flush()
            output.fd.sync()
        }
    }
    private fun markPending(uri: Uri, pending: Boolean) {
        val updated = resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, if (pending) 1 else 0)
        }, null, null)
        check(updated == 1) { "Не удалось ${if (pending) "заблокировать" else "опубликовать"} QA-копию" }
    }
    private fun updateJpeg(temp: File, historical: Long) {
        val date = Instant.ofEpochMilli(historical).atZone(ZoneId.systemDefault())
        val text = date.format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
        val offset = date.offset.id.let { if (it == "Z") "+00:00" else it }
        val exif = ExifInterface(temp.absolutePath)
        exif.setAttribute(ExifInterface.TAG_DATETIME, text)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, text)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, text)
        if (Build.VERSION.SDK_INT >= 30) {
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset)
        }
        exif.saveAttributes()
        check(ExifInterface(temp.absolutePath)
            .getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == text) {
            "Дата JPEG не сохранилась во временном файле"
        }
    }
    private data class VideoDetails(val duration: Long, val width: Int, val height: Int,
        val rotation: Int, val hasAudio: String?)
    private fun videoDetails(file: File): VideoDetails {
        val m = MediaMetadataRetriever()
        try {
            m.setDataSource(file.absolutePath)
            fun int(key: Int) = m.extractMetadata(key)?.toIntOrNull() ?: 0
            return VideoDetails(
                m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: error("Видео не воспроизводится"),
                int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO),
            )
        } finally { m.release() }
    }
    private suspend fun updateMp4(input: File, result: File, historical: Long) {
        val local = Instant.ofEpochMilli(historical).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))
        val args = arrayOf("-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-i", input.absolutePath, "-map", "0", "-map_metadata", "0", "-c", "copy",
            "-metadata", "creation_time=${Instant.ofEpochMilli(historical)}",
            "-metadata", "com.apple.quicktime.creationdate=$local",
            "-movflags", "+faststart", result.absolutePath)
        val success = suspendCancellableCoroutine<Boolean> { cont ->
            val session = FFmpegKit.executeWithArgumentsAsync(args) { completed ->
                if (cont.isActive) cont.resume(ReturnCode.isSuccess(completed.returnCode))
            }
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
        check(success && result.isFile && result.length() > 0L) { "MP4 remux не удался" }
        val before = videoDetails(input)
        val after = videoDetails(result)
        check(before.duration > 0 && after.duration > 0 &&
            abs(before.duration - after.duration) <= max(1_000L, before.duration / 100) &&
            before.width == after.width && before.height == after.height &&
            before.rotation == after.rotation && before.hasAudio == after.hasAudio) {
            "MP4 изменил длительность, геометрию, ориентацию или звук"
        }
    }
    private suspend fun scanOwnedTestFile(item: Item, k: Kind): String {
        val path = item.data ?: return "путь Android не предоставлен"
        val file = try { File(path).canonicalFile } catch (_: Exception) { return "путь недоступен" }
        val root = Environment.getExternalStorageDirectory().canonicalPath.trimEnd('/') + "/"
        if (!file.path.startsWith(root) || !file.path.contains("/" + TRIAL_PATH))
            return "сканирование пропущено: путь не относится к QA"
        val scanned = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<Uri?> { cont ->
                MediaScannerConnection.scanFile(context, arrayOf(file.path),
                    arrayOf(k.mime)) { _, indexedUri ->
                    if (cont.isActive) cont.resume(indexedUri)
                }
            }
        }
        return scanned?.toString() ?: "MediaScanner не вернул URI"
    }

    /** Only change filesystem mtime of a VERIFIED app-owned disposable QA copy.
     * DATE_MODIFIED is read-only in MediaStore and must be refreshed by scan.
     * This does NOT create another MediaStore row, move a file, or rewrite bytes.
     */
    private fun setQaFileMtime(item: Item, historicalMillis: Long): Long {
        check(item.owner == context.packageName && item.path == TRIAL_PATH &&
            item.name?.startsWith("ML_SAME_URI_") == true) {
            "Запрещено менять дату файла вне однокопийной QA-лаборатории"
        }
        check(MediaDateRepairPolicy.validTime(historicalMillis, System.currentTimeMillis())) {
            "Недопустимая историческая дата"
        }
        val data = item.data ?: error("Android не предоставляет путь тестового файла")
        val file = File(data).canonicalFile
        val root = Environment.getExternalStorageDirectory().canonicalPath.trimEnd('/') + "/"
        check(file.path.startsWith(root + TRIAL_PATH) && file.isFile) {
            "Путь не принадлежит тестовой директории"
        }
        check(file.setLastModified(historicalMillis)) {
            "Android запретил восстановить время изменения QA-файла"
        }
        val actual = file.lastModified()
        check(abs(actual - historicalMillis) <= 2000L) {
            "Время изменения файла не сохранилось: $actual"
        }
        return actual
    }

    /** Rerun lastModified-only on the existing 1.7.164 QA sample: stable URI. */
    suspend fun repairExistingTrialMtime(k: Kind): Result = withContext(Dispatchers.IO) {
        val j = journalRead(k) ?: return@withContext Result(false,
            "Нет прежней ${k.label} QA-копии. Создайте её через 'изменение без нового URI'.")
        try {
            check(j.optString("phase") in setOf("VERIFIED", "MTIME_VERIFIED")) {
                "QA-проба ещё не проверена или уже откатана. Не меняем её."
            }
            val (uri, before) = safeJournalCopy(k, j)
            val expectedHash = j.getString("expectedHash") to j.getLong("expectedSize")
            check(checksum(uri) == expectedHash) { "Тестовые байты отличаются от ожидаемых" }
            val sourceUri = Uri.parse(j.getString("sourceUri"))
            val sourceHash = j.getString("sourceHash") to j.getLong("sourceSize")
            check(checksum(sourceUri) == sourceHash) {
                "Оригинал изменён или недоступен, опыт остановлен"
            }
            val historic = j.getLong("historical")
            val originalModified = before.modified
            val fileMtime = setQaFileMtime(before, historic)
            val scanUri = scanOwnedTestFile(before, k)
            val after = assertTrialIdentity(uri, j.getString("name"), before.id, k)
            assertOnlyOneTrial(after, k)
            check(checksum(uri) == expectedHash && checksum(sourceUri) == sourceHash &&
                dao.countSavedUriReferences(uri.toString()) == 0) {
                "Нарушена проверка неизменности тестовой копии, оригинала или ссылок"
            }
            val modifiedMatches = abs(after.modified - historic) <= 2000L
            val takenMatches = abs(after.taken - historic) <= 60_000L
            j.put("phase", "MTIME_VERIFIED")
                .put("beforeMtimeRepair", originalModified)
                .put("afterMtimeRepair", after.modified)
                .put("scannerUriMtimeRepair", scanUri)
            journalWrite(k, j)
            Result(modifiedMatches && takenMatches,
                "${k.label}: повторная проверка БЕЗ пересоздания файла. " +
                "MediaStore ID: ${before.id} -> ${after.id}, URI прежний. " +
                "DATE_TAKEN: ${before.taken} -> ${after.taken} " +
                "(${if (takenMatches) "совпадает" else "НЕ совпадает"}). " +
                "DATE_MODIFIED: $originalModified -> ${after.modified} " +
                "(${if (modifiedMatches) "совпадает" else "НЕ совпадает"}). " +
                "DATE_ADDED: ${before.added} -> ${after.added}. " +
                "file.lastModified=$fileMtime. Сканер: $scanUri. " +
                "Проверьте 'Сегодня' и историческую ленту Xiaomi, не удаляя пробу " +
                "до проверки. Дата файлов ML и их ссылки не менялись.")
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result(false, "${k.label}: ${error.message}. " +
            "Реальные файлы ML не изменялись.") }
    }

    /** The only write target is a NEW, owned, uniquely named QA copy. */
    suspend fun runTrial(k: Kind): Result = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 29) { "Для опыта нужен Android 10+" }
        check(journalRead(k) == null && !backup(k).exists()) {
            "Сначала очистите старую QA-пробу этого формата"
        }
        val rows = dao.all()
        check(rows.none { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }) {
            "Завершите загрузки перед безопасной пробой"
        }
        val refs = rows.mapNotNull { it.savedUri }.groupingBy { it }.eachCount()
        val selected = rows.asSequence().filter { it.savedUri != null && refs[it.savedUri] == 1 }
            .mapNotNull { validCandidate(it, k) }.sortedBy { it.size }
            .take(40).firstOrNull { dao.countSavedUriReferences(it.sourceUri.toString()) == 1 }
            ?: return@withContext Result(false, "Нет однозначного app-owned ${k.label} до лимита пробы. Ничего не изменено.")
        val sourceUri = selected.sourceUri
        val sourceHash = checksum(sourceUri)
        check(sourceHash.second == selected.size) { "Исходник изменился до проверки" }
        val free = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        check(free > selected.size * 4L + RESERVE) { "Недостаточно места для резерва и QA-копии" }
        val backupFile = backup(k)
        var transformed: File? = null
        try {
            resolver.openInputStream(sourceUri)?.use { input ->
                backupFile.outputStream().use { input.copyTo(it) }
            } ?: error("Не удалось создать резервную копию")
            check(checksum(backupFile) == sourceHash) { "SHA-256 исходника и резерва различаются" }
            check(validCandidate(dao.byId(selected.row.id)
                ?: error("Исчезла исходная запись"), k) == selected &&
                dao.countSavedUriReferences(sourceUri.toString()) == 1 &&
                checksum(sourceUri) == sourceHash) { "Исходная запись изменилась" }
            val name = "ML_SAME_URI_${k.label}_${UUID.randomUUID().toString().take(12)}.${k.extension}"
            val j = JSONObject().put("name", name).put("testUri", "")
                .put("sourceUri", sourceUri.toString()).put("sourceId", selected.row.id)
                .put("sourceHash", sourceHash.first).put("sourceSize", sourceHash.second)
                .put("baselineHash", sourceHash.first).put("baselineSize", sourceHash.second)
                .put("historical", selected.row.createdAtEpochMillis)
                .put("phase", "PREPARED")
            journalWrite(k, j) // Durable name BEFORE MediaStore insert: recover orphan by name.
            val testUri = resolver.insert(mediaKind(k), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, k.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, TRIAL_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("MediaStore отказал в создании отдельной QA-копии")
            j.put("testUri", testUri.toString()).put("phase", "CREATED")
            journalWrite(k, j)
            check(testUri != sourceUri && dao.countSavedUriReferences(testUri.toString()) == 0) {
                "Пробный URI ошибочно совпал с реальным файлом ML"
            }
            val inserted = inspect(testUri) ?: error("Не найдена пробная запись MediaStore")
            val initialId = inserted.id
            assertTrialIdentity(testUri, name, initialId, k)
            resolver.openOutputStream(testUri, "w")?.use { out ->
                backupFile.inputStream().use { it.copyTo(out) }
            } ?: error("Не удалось записать исходные байты в QA-копию")
            check(checksum(testUri) == sourceHash) { "QA-база не совпала с оригиналом" }
            markPending(testUri, false)
            val before = assertTrialIdentity(testUri, name, initialId, k)
            assertOnlyOneTrial(before, k)
            j.put("testId", initialId).put("beforeTaken", before.taken)
                .put("beforeAdded", before.added).put("beforeModified", before.modified)
                .put("phase", "BASELINE")
            journalWrite(k, j)
            transformed = File.createTempFile("qa-dated-", ".${k.extension}", context.cacheDir)
            when (k) {
                Kind.JPEG -> {
                    backupFile.copyTo(transformed!!, overwrite = true)
                    updateJpeg(transformed!!, selected.row.createdAtEpochMillis)
                }
                Kind.MP4 -> updateMp4(backupFile, transformed!!,
                    selected.row.createdAtEpochMillis)
            }
            val finalHash = checksum(transformed!!)
            check(finalHash.second > 0 && checksum(sourceUri) == sourceHash) {
                "Файл источника изменился во время подготовки"
            }
            // Nothing can reach the destructive "rwt" unless identity, owner,
            // exact QA folder/name/MediaStore ID and zero history refs were rechecked.
            assertTrialIdentity(testUri, name, initialId, k)
            check(dao.countSavedUriReferences(testUri.toString()) == 0) {
                "На тестовый URI появилась ссылка в реальной истории"
            }
            markPending(testUri, true)
            j.put("phase", "WRITING").put("expectedHash", finalHash.first)
                .put("expectedSize", finalHash.second)
            journalWrite(k, j)
            writeSameUri(testUri, transformed!!)
            check(checksum(testUri) == finalHash) { "Ошибка SHA-256 после записи в тот же URI" }
            markPending(testUri, false)
            j.put("phase", "PUBLISHED").also { journalWrite(k, j) }
            // Regression fix: earlier successful QA 1.7.162/163 set filesystem mtime
            // to the historical value before rescanning. 1.7.164 omitted it.
            setQaFileMtime(assertTrialIdentity(testUri, name, initialId, k),
                selected.row.createdAtEpochMillis)
            val scanUri = scanOwnedTestFile(assertTrialIdentity(testUri, name, initialId, k), k)
            // After scanner, a new MediaStore _ID or duplicate makes this lab FAIL,
            // even when a Xiaomi thumbnail seems to have the right date.
            val after = assertTrialIdentity(testUri, name, initialId, k)
            assertOnlyOneTrial(after, k)
            check(checksum(testUri) == finalHash && checksum(sourceUri) == sourceHash &&
                dao.countSavedUriReferences(testUri.toString()) == 0 &&
                validCandidate(dao.byId(selected.row.id) ?: error("История пропала"), k) == selected) {
                "Файл, исходник или запись ML изменились при сканировании"
            }
            j.put("phase", "VERIFIED").put("afterTaken", after.taken)
                .put("afterAdded", after.added).put("afterModified", after.modified)
                .put("scannerUri", scanUri)
            journalWrite(k, j)
            val match = abs(after.taken - selected.row.createdAtEpochMillis) <= 60_000L
            val mtimeMatch = abs(after.modified - selected.row.createdAtEpochMillis) <= 2000L
            Result(match && mtimeMatch, "${k.label}: QA-копия перезаписана через прежний URI (ID=$initialId). " +
                "Дата из истории ML: ${Instant.ofEpochMilli(selected.row.createdAtEpochMillis).atZone(ZoneId.systemDefault())}. " +
                "DATE_TAKEN ДО: ${before.taken}; ПОСЛЕ: ${after.taken} " +
                "(${if (match) "совпадает" else "не совпадает"}). " +
                "DATE_MODIFIED ДО: ${before.modified}; ПОСЛЕ: ${after.modified} " +
                "(${if (mtimeMatch) "совпадает" else "НЕ совпадает"}). " +
                "DATE_ADDED ДО: ${before.added}; ПОСЛЕ: ${after.added}. " +
                "Сканер: $scanUri. Исходник и ссылки ML не менялись. " +
                "Проверьте позицию в Xiaomi, затем 'Откатить' и 'Удалить QA-копию'.")
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) {
            Result(false, "Опыт ${k.label} остановлен: ${e.message}. Оригинал и Room не изменялись. " +
                "Тестовая копия могла остаться: откатите/удалите её в лаборатории.")
        } finally { transformed?.delete() }
    }

    private fun locateJournalUri(j: JSONObject, k: Kind): Uri? {
        val stored = j.optString("testUri")
        if (stored.isNotBlank()) return Uri.parse(stored)
        val name = j.getString("name")
        return resolver.query(mediaKind(k), arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(TRIAL_PATH, name), null)?.use { c ->
                if (!c.moveToFirst() || c.count != 1) null else
                    Uri.withAppendedPath(mediaKind(k),
                        c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)).toString())
        }
    }
    private suspend fun safeJournalCopy(k: Kind, j: JSONObject): Pair<Uri, Item> {
        val uri = locateJournalUri(j, k) ?: error("Не найдена однозначная QA-копия")
        val item = assertTrialIdentity(uri, j.getString("name"),
            if (j.has("testId")) j.getLong("testId") else
                uri.pathSegments.last().toLong(), k)
        check(dao.countSavedUriReferences(uri.toString()) == 0 &&
            uri.toString() != j.getString("sourceUri")) { "Пробная копия уже связана с реальной историей" }
        assertOnlyOneTrial(item, k)
        return uri to item
    }
    suspend fun restoreTrial(k: Kind): Result = withContext(Dispatchers.IO) {
        val j = journalRead(k) ?: return@withContext Result(false, "Нет QA-пробы")
        try {
            val (uri, item) = safeJournalCopy(k, j)
            val file = backup(k)
            check(file.isFile && checksum(file) ==
                (j.getString("baselineHash") to j.getLong("baselineSize"))) {
                "Резервная копия недоступна или повреждена. Откат невозможен."
            }
            val current = checksum(uri)
            val expected = (j.optString("expectedHash") to j.optLong("expectedSize"))
            val baseline = (j.getString("baselineHash") to j.getLong("baselineSize"))
            check(current == expected || current == baseline) {
                "QA-файл повреждён или изменён: автоматическая перезапись заблокирована"
            }
            if (current != baseline) {
                markPending(uri, true)
                j.put("phase", "RESTORING").also { journalWrite(k, j) }
                writeSameUri(uri, file)
                check(checksum(uri) == baseline) { "Откат SHA-256 не прошёл" }
                markPending(uri, false)
            }
            // The QA rollback returns both baseline BYTES and its former mtime.
            // For 1.7.164 journals without beforeModified use original DATE_ADDED.
            setQaFileMtime(assertTrialIdentity(uri, j.getString("name"), item.id, k),
                j.optLong("beforeModified", j.optLong("beforeAdded")))
            scanOwnedTestFile(item, k)
            val after = assertTrialIdentity(uri, j.getString("name"), item.id, k)
            check(checksum(uri) == baseline) { "Резервный файл не восстановлен" }
            j.put("phase", "RESTORED").put("afterRestoreTaken", after.taken)
            journalWrite(k, j)
            Result(true, "${k.label}: откат выполнен через прежний URI. MediaStore ID=${after.id}, " +
                "SHA-256 исходной тестовой копии восстановлен, Room и оригинал не тронуты.")
        } catch (e: Exception) { Result(false, "Откат ${k.label}: ${e.message}") }
    }
    suspend fun removeTrial(k: Kind): Result = withContext(Dispatchers.IO) {
        val j = journalRead(k)
        if (j == null) {
            // The app can die after saving its private backup but before the
            // journal is created. Remove only this app-private orphan backup.
            if (backup(k).exists()) {
                check(backup(k).delete()) { "Не удалось очистить незавершённый резерв" }
                return@withContext Result(true, "Убран незавершённый QA-резерв ${k.label}")
            }
            return@withContext Result(true, "QA-копии ${k.label} нет")
        }
        try {
            if (j.optString("phase") == "PREPARED" && locateJournalUri(j, k) == null) {
                // No MediaStore copy was ever committed. Both items are strictly
                // internal to the QA experiment: never touch a downloaded file.
                check(!backup(k).exists() || backup(k).delete()) {
                    "Не удалось удалить незавершённый QA-резерв"
                }
                journal(k).delete()
                return@withContext Result(true, "Незавершённая QA-проба ${k.label} очищена")
            }
            val (uri, item) = safeJournalCopy(k, j)
            val current = checksum(uri)
            val baseline = j.getString("baselineHash") to j.getLong("baselineSize")
            val expected = j.optString("expectedHash") to j.optLong("expectedSize")
            // A partially written QA file is safe to delete ONLY when it is
            // still IS_PENDING. Nothing in the user library can be deleted here.
            val pending = resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING),
                null, null, null)?.use { c -> c.moveToFirst() &&
                    c.getInt(c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)) == 1 } ?: false
            check(current == baseline || current == expected ||
                (pending && j.optString("phase") in listOf("CREATED", "WRITING", "RESTORING"))) {
                "QA-копия неожиданно изменилась. Удаление заблокировано."
            }
            check(item.owner == context.packageName && item.path == TRIAL_PATH)
            check(resolver.delete(uri, null, null) == 1) { "Android отказал в удалении QA-копии" }
            journal(k).delete()
            backup(k).delete()
            Result(true, "Удалена только тестовая ${k.label}-копия. Существующие файлы и ссылки ML не менялись.")
        } catch (e: Exception) { Result(false, "Удаление QA ${k.label} остановлено: ${e.message}") }
    }
}

/** Pure fail-closed identity checks for in-place experiments. */
internal object InPlaceTrialPolicy {
    fun ownedTrial(owner: String?, packageName: String, path: String?, name: String?,
        expectedName: String, mime: String?, expectedMime: String,
        mediaStoreId: Long, expectedId: Long): Boolean =
        owner == packageName && path == MediaInPlaceChronologyTrial.TRIAL_PATH &&
        name == expectedName && expectedName.startsWith("ML_SAME_URI_") &&
        mime == expectedMime && expectedId > 0L && mediaStoreId == expectedId
}
