package com.domenota.medialoader.data.storage

import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import com.domenota.medialoader.core.database.DownloadDao
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * TEMPORARY Xiaomi Gallery lab. Test one distinct JPEG without pre-existing EXIF dates.
 * Writes synthetic "date taken" metadata from history into an isolated COPY only.
 * The true shooting date is unknown; the original media, Room, and favorites are untouched.
 * MediaStore.DATE_ADDED cannot be overwritten and is intentionally not written.
 */
internal class MediaExifChronologyTrial(
    private val context: Context,
    private val dao: DownloadDao,
) {
    companion object {
        const val TRIAL_PATH = "Pictures/MediaLoader_QA_EXIF_Test/"
        private const val RESERVE_BYTES = 96L * 1024 * 1024
    }

    data class Source(val id: String, val uri: String, val name: String,
        val bytes: Long, val originalMillis: Long)
    data class Result(val completed: Boolean, val message: String)

    private val resolver get() = context.contentResolver
    private val journal = AtomicFile(File(context.noBackupFilesDir, "ml-qa-exif-one-copy.json"))

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

    private fun readSource(row: DownloadEntity): Source? {
        if (Build.VERSION.SDK_INT < 29 || row.state != DownloadState.COMPLETED ||
            row.hidden || row.isPrivate || row.mediaType != MediaType.PHOTO ||
            !MediaDateRepairPolicy.validTime(row.createdAtEpochMillis, System.currentTimeMillis()) ||
            row.savedUri.isNullOrBlank()) return null
        val uri = Uri.parse(row.savedUri)
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return null
        return try {
            resolver.query(uri, arrayOf(
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
            ), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                fun str(s: String) = c.getString(c.getColumnIndexOrThrow(s))
                fun num(s: String) = c.getLong(c.getColumnIndexOrThrow(s))
                if (!ExifTrialPolicy.eligible(str(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                    context.packageName, str(MediaStore.MediaColumns.RELATIVE_PATH),
                    str(MediaStore.MediaColumns.DISPLAY_NAME), row.originalName,
                    str(MediaStore.MediaColumns.MIME_TYPE), num(MediaStore.MediaColumns.SIZE),
                    num(MediaStore.MediaColumns.DATE_ADDED) * 1000L,
                    row.createdAtEpochMillis)) return@use null
                Source(row.id, row.savedUri!!, row.originalName,
                    num(MediaStore.MediaColumns.SIZE), row.createdAtEpochMillis)
            }
        } catch (_: Exception) { null }
    }

    private fun sha(uri: Uri): Pair<String, Long> {
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
        } ?: error("Файл недоступен для проверки SHA-256")
        return digest.digest().joinToString("") { "%02x".format(it) } to size
    }

    private fun shaFile(file: File): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        FileInputStream(file).use { input ->
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

    private fun sourceHasRealExifDate(file: File): Boolean {
        val exif = ExifInterface(file.absolutePath)
        return ExifTrialPolicy.hasAnyDate(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
            exif.getAttribute(ExifInterface.TAG_DATETIME),
        )
    }

    private fun writeSyntheticExif(file: File, epochMillis: Long): String {
        val zdt = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        val textDate = zdt.format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
        val offset = zdt.offset.id.let { if (it == "Z") "+00:00" else it }
        val exif = ExifInterface(file.absolutePath)
        check(!ExifTrialPolicy.hasAnyDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
            exif.getAttribute(ExifInterface.TAG_DATETIME))) {
            "У оригинала есть реальная EXIF-дата: пропускаем во избежание подмены"
        }
        exif.setAttribute(ExifInterface.TAG_DATETIME, textDate)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, textDate)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, textDate)
        if (Build.VERSION.SDK_INT >= 30) {
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset)
        }
        exif.saveAttributes()
        file.setLastModified(epochMillis)
        val verified = ExifInterface(file.absolutePath)
        check(verified.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == textDate) {
            "Пробный EXIF не сохранил исходную дату из истории"
        }
        return "$textDate $offset"
    }

    /** Only explicitly invoked, never started during app installation or startup. */
    suspend fun runTrial(): Result = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 29) { "Для эксперимента требуется Android 10+" }
        check(readJournal() == null) {
            "Сначала удалите предыдущую EXIF-копию. Повторно создавать её нельзя."
        }
        val rows = dao.all()
        check(rows.none { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }) {
            "Завершите текущие загрузки перед пробой"
        }
        val frequency = rows.mapNotNull { it.savedUri }.groupingBy { it }.eachCount()
        val scratchDir = File(context.cacheDir, "qa-exif-chronology").apply { mkdirs() }
        var selected: Source? = null
        var scratch: File? = null
        var sourceHash: Pair<String, Long>? = null
        try {
            // Check at most 24 distinct historical JPGs; never overwrite existing EXIF shooting dates.
            for (source in rows.asSequence().filter { it.savedUri != null &&
                frequency[it.savedUri] == 1 }.mapNotNull(::readSource)
                .sortedBy { it.originalMillis }.take(24)) {
                if (dao.countSavedUriReferences(source.uri) != 1) continue
                val temp = File.createTempFile("candidate-", ".jpg", scratchDir)
                try {
                    val baseline = sha(Uri.parse(source.uri))
                    if (baseline.second != source.bytes) continue
                    resolver.openInputStream(Uri.parse(source.uri))!!.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (shaFile(temp) != baseline || sourceHasRealExifDate(temp)) continue
                    selected = source
                    scratch = temp
                    sourceHash = baseline
                    break
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* unsupported, skip without modifying source */ }
                finally { if (scratch !== temp) temp.delete() }
            }
            val source = selected ?: return@withContext Result(false,
                "Не нашлось JPEG без настоящей EXIF-даты среди проверенных 24 файлов. Ничего не изменено.")
            val temp = scratch ?: error("Не найден временный файл")
            val oldHash = sourceHash ?: error("Нет контрольной суммы")
            check(readSource(dao.byId(source.id) ?: error("История изменилась")) == source &&
                dao.countSavedUriReferences(source.uri) == 1 &&
                sha(Uri.parse(source.uri)) == oldHash) { "Исходник изменился: проба отменена" }
            val free = android.os.StatFs(Environment.getExternalStorageDirectory().path).availableBytes
            check(free > source.bytes * 3 + RESERVE_BYTES) { "Недостаточно места для безопасной пробы" }
            val historical = writeSyntheticExif(temp, source.originalMillis)
            val testHash = shaFile(temp)
            val name = "ML_EXIF_QA_${System.currentTimeMillis()}_HISTORICAL.jpg"
            val entry = JSONObject()
                .put("name", name).put("copyUri", "")
                .put("sourceId", source.id).put("sourceUri", source.uri)
                .put("sourceSha256", oldHash.first)
                .put("sourceSize", oldHash.second)
                .put("expectedSha256", testHash.first).put("expectedSize", testHash.second)
                .put("historicalMillis", source.originalMillis)
            writeJournal(entry) // Reserve name BEFORE creating the MediaStore item.
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, TRIAL_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val copyUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать EXIF-копию в MediaStore")
            entry.put("copyUri", copyUri.toString())
            writeJournal(entry)
            resolver.openOutputStream(copyUri, "w")?.use { output ->
                temp.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Android запретил запись пробной EXIF-копии")
            check(sha(copyUri) == testHash) { "Контрольная сумма EXIF-копии отличается" }
            check(sha(Uri.parse(source.uri)) == oldHash) { "Исходная фотография изменилась" }
            check(resolver.update(copyUri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 1) { "Не удалось опубликовать копию" }
            entry.put("published", true)
            writeJournal(entry)
            // A scanner may index DATE_TAKEN asynchronously; never mistake this for DATE_ADDED.
            val scanPath = resolver.query(copyUri,
                arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else
                    c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
            }
            scanPath?.let { path ->
                val file = File(path).canonicalFile
                val root = Environment.getExternalStorageDirectory().canonicalPath.trimEnd('/') + "/"
                if (file.path.startsWith(root) && file.path.contains("/" + TRIAL_PATH)) {
                    file.setLastModified(source.originalMillis)
                    MediaScannerConnection.scanFile(context, arrayOf(file.path),
                        arrayOf("image/jpeg"), null)
                }
            }
            delay(850)
            val dates = resolver.query(copyUri,
                arrayOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED),
                null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else Pair(
                    c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)),
                    c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)) * 1000L)
            }
            entry.put("indexedTakenMillis", dates?.first ?: 0L)
                .put("indexedAddedMillis", dates?.second ?: 0L)
            writeJournal(entry)
            val matched = dates?.first?.let { kotlin.math.abs(it - source.originalMillis) <= 60_000L }
            Result(true, "Готова 1 EXIF-копия: $name. Историческая дата: $historical. " +
                "MediaStore.DATE_TAKEN: ${if (matched == true) "совпадает" else "не подтверждена"}; " +
                "DATE_ADDED осталась датой нового добавления. " +
                "Проверьте размещение в Xiaomi Gallery, затем удалите копию здесь.")
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            Result(false, "EXIF-проба остановлена: ${error.message ?: "ошибка"}. " +
                "Оригинал не менялся. При необходимости удалите пробу через кнопку.")
        } finally { scratch?.delete() }
    }

    /** Delete only the journaled COPY after exact URI/owner/name/path/SHA validation. */
    suspend fun removeTrial(): Result = withContext(Dispatchers.IO) {
        val entry = readJournal() ?: return@withContext Result(true, "Пробной EXIF-копии нет")
        val expectedName = entry.getString("name")
        val knownUri = entry.optString("copyUri")
        val uri = if (knownUri.isNotBlank()) Uri.parse(knownUri) else {
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(TRIAL_PATH, expectedName), null)?.use { c ->
                if (!c.moveToFirst() || c.count != 1) null else Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)).toString())
            }
        }
        if (uri == null) return@withContext Result(false,
            "Не удалось идентифицировать пробу; оригиналы не затронуты")
        val owned = try {
            resolver.query(uri, arrayOf(
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use { c ->
                c.moveToFirst() &&
                    c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)) == context.packageName &&
                    c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)) == TRIAL_PATH &&
                    c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) == expectedName &&
                    (c.getInt(c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)) == 1 ||
                        sha(uri) == Pair(entry.getString("expectedSha256"), entry.getLong("expectedSize")))
            } ?: false
        } catch (_: Exception) { false }
        if (!owned) return@withContext Result(false,
            "Копия изменилась или не подтверждена. Удаление остановлено, исходники сохранены.")
        if (resolver.delete(uri, null, null) != 1) return@withContext Result(false,
            "Android отказал в удалении пробной копии")
        journal.delete()
        Result(true, "Удалена только EXIF-копия $expectedName. Исходная фотография сохранена.")
    }
}

/** Pure checks used by CI to prevent touching sources or rewriting genuine EXIF dates. */
internal object ExifTrialPolicy {
    fun hasAnyDate(original: String?, digitized: String?, edited: String?) =
        !original.isNullOrBlank() || !digitized.isNullOrBlank() || !edited.isNullOrBlank()

    fun eligible(owner: String?, ourPackage: String, relativePath: String?, name: String?,
        originalName: String, mimeType: String?, size: Long, addedMillis: Long,
        historicalMillis: Long): Boolean =
        owner == ourPackage && relativePath == "Pictures/MediaLoader/" &&
            name != null && name == originalName &&
            (name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)) &&
            mimeType == "image/jpeg" && size in 1..(12L * 1024 * 1024) &&
            historicalMillis in 946684800000L..System.currentTimeMillis() &&
            addedMillis > historicalMillis + 86_400_000L
}