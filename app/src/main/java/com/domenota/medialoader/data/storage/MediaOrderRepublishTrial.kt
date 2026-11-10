package com.domenota.medialoader.data.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import com.domenota.medialoader.core.database.DownloadDao
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Isolated, user-confirmed, three-picture experiment for Xiaomi Gallery's
 * reverse-chronology request.
 *
 * Copies are published oldest -> newest into a SEPARATE QA album. Android
 * assigns TODAY's DATE_ADDED to these new MediaStore items. The experiment
 * can only establish whether chronological insertion order changes their
 * relative position; it CANNOT restore historical dates.
 *
 * Never moves or deletes sources, never edits Room/history/private album,
 * never rewrites EXIF or inserts synthetic DATE_ADDED/DATE_TAKEN.
 */
internal class MediaOrderRepublishTrial(
    private val context: Context,
    private val dao: DownloadDao,
) {
    companion object {
        const val TRIAL_PATH = "Pictures/MediaLoader_QA_Order_Test/"
        private const val MAX_SAMPLE_BYTES = 24L * 1024 * 1024
        private const val SAFETY_MARGIN_BYTES = 128L * 1024 * 1024
    }

    data class Source(
        val historyId: String, val savedUri: String,
        val fileName: String, val mimeType: String, val fileSize: Long,
        val originalMillis: Long, val dateAddedSeconds: Long,
    )
    data class Outcome(val copies: Int, val message: String)

    private val resolver get() = context.contentResolver
    private val journal = AtomicFile(File(context.noBackupFilesDir,
        "ml-gallery-republish-trial-entries.json"))

    private fun loadJournal(): JSONArray = try {
        JSONArray(journal.openRead().bufferedReader().use { it.readText() })
    } catch (_: java.io.FileNotFoundException) {
        JSONArray()
    }

    private fun writeJournal(entries: JSONArray) {
        val output = journal.startWrite()
        try {
            output.write(entries.toString().toByteArray(Charsets.UTF_8))
            journal.finishWrite(output)
        } catch (error: Throwable) {
            journal.failWrite(output)
            throw error
        }
    }

    private fun sha256(uri: Uri): Pair<String, Long> {
        val sha = MessageDigest.getInstance("SHA-256")
        var size = 0L
        resolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(bytes)
                if (n < 0) break
                sha.update(bytes, 0, n)
                size += n
            }
        } ?: error("Не удалось прочитать файл для проверки")
        return sha.digest().joinToString("") { "%02x".format(it) } to size
    }

    private fun readSource(row: DownloadEntity): Source? {
        if (Build.VERSION.SDK_INT < 29 || row.state != DownloadState.COMPLETED ||
            row.isPrivate || row.hidden || row.mediaType != MediaType.PHOTO ||
            row.createdAtEpochMillis <= 0 || row.savedUri.isNullOrBlank()) return null
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
                fun str(key: String) = c.getString(c.getColumnIndexOrThrow(key))
                fun long(key: String) = c.getLong(c.getColumnIndexOrThrow(key))
                val owner = str(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                val relative = str(MediaStore.MediaColumns.RELATIVE_PATH)
                val name = str(MediaStore.MediaColumns.DISPLAY_NAME)
                val mime = str(MediaStore.MediaColumns.MIME_TYPE)
                val size = long(MediaStore.MediaColumns.SIZE)
                val added = long(MediaStore.MediaColumns.DATE_ADDED)
                if (!RepublishTrialPolicy.eligible(owner, context.packageName,
                        relative, name, row.originalName, mime,
                        size, added, row.createdAtEpochMillis)) return@use null
                Source(row.id, row.savedUri!!, name, mime, size,
                    row.createdAtEpochMillis, added)
            }
        } catch (_: Exception) { null }
    }

    suspend fun select(): List<Source> = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 29) { "Требуется Android 10 или новее." }
        val rows = dao.all()
        check(rows.none { it.state == DownloadState.RUNNING ||
            it.state == DownloadState.QUEUED }) {
            "Пробный перенос запрещён, пока идут загрузки."
        }
        val frequency = rows.mapNotNull { it.savedUri }.groupingBy { it }.eachCount()
        val candidates = rows.asSequence()
            .filter { it.savedUri != null && frequency[it.savedUri] == 1 }
            .mapNotNull(::readSource)
            .sortedWith(compareBy({ it.originalMillis }, { it.historyId }))
            .toList()
        RepublishTrialPolicy.selectThree(candidates)
    }

    /**
     * Only THREE distinct copies; all originals stay untouched.
     * Each copy has its own durable journal entry and verified SHA-256.
     * The test gallery album is deliberately separate from Pictures/MediaLoader.
     */
    suspend fun runTrial(onProgress: (Int, Int) -> Unit = { _, _ -> }): Outcome =
        withContext(Dispatchers.IO) {
            check(loadJournal().length() == 0) {
                "Предыдущие пробные копии ещё существуют. Сначала удалите их кнопкой отмены."
            }
            val selected = select()
            check(selected.size == 3) {
                "Для пробной пересборки нужны три подходящие фотографии без конфликтов."
            }
            val totalBytes = selected.sumOf { it.fileSize }
            val freeBytes = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
            check(freeBytes > totalBytes * 2 + SAFETY_MARGIN_BYTES) {
                "Недостаточно места для копий и проверки. Исходники не изменены."
            }
            val runId = System.currentTimeMillis().toString()
            val appended = JSONArray()
            val newAdded = ArrayList<Long>(3)
            for ((index, source) in selected.withIndex()) {
                currentCoroutineContext().ensureActive()
                val latest = dao.byId(source.historyId)
                check(latest?.savedUri == source.savedUri && !latest.isPrivate &&
                    !latest.hidden && dao.countSavedUriReferences(source.savedUri) == 1 &&
                    readSource(latest) == source) {
                    "Один из исходников изменился во время проверки. Операция остановлена."
                }
                val extension = source.fileName.substringAfterLast('.', "")
                check(extension.matches(Regex("[A-Za-z0-9]{2,5}"))) {
                    "Неподдерживаемое расширение файла. Проба остановлена."
                }
                // A simple numbered name makes chronological order visible
                // regardless of the original pictures' names.
                val tag = listOf("01_OLD", "02_MIDDLE", "03_NEW")[index]
                val fileName = "ML_QA_" + runId + "_" + tag + "." + extension
                if (index > 0) {
                    // DATE_ADDED is stored in seconds, so different insertions
                    // must not share a timestamp.
                    while (System.currentTimeMillis() / 1000 <= newAdded.last()) {
                        currentCoroutineContext().ensureActive()
                        delay(250)
                    }
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, source.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, TRIAL_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val dest = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Android не разрешил создать пробную копию.")
                // Write the exact new URI before ever opening it for write.
                val record = JSONObject()
                    .put("sourceHistoryId", source.historyId)
                    .put("sourceUri", source.savedUri)
                    .put("copyUri", dest.toString())
                    .put("name", fileName)
                    .put("relativePath", TRIAL_PATH)
                    .put("size", source.fileSize)
                    .put("sha256", "")
                    .put("originalMillis", source.originalMillis)
                    .put("published", false)
                try {
                    appended.put(record)
                    writeJournal(appended)
                } catch (error: Throwable) {
                    // This is only the newly created test placeholder.
                    runCatching { resolver.delete(dest, null, null) }
                    throw error
                }

                val sourceUri = Uri.parse(source.savedUri)
                try {
                    val sourceDigest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    val input = resolver.openInputStream(sourceUri)
                        ?: error("Потерян доступ к исходной фотографии")
                    input.use { src ->
                        val output = resolver.openOutputStream(dest, "w")
                            ?: error("Невозможно записать пробную копию")
                        output.use { dst ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = src.read(buffer)
                                if (n < 0) break
                                dst.write(buffer, 0, n)
                                sourceDigest.update(buffer, 0, n)
                                count += n
                            }
                            dst.flush()
                        }
                    }
                    check(count == source.fileSize) { "Размер копии не совпадает с источником" }
                    val expectedHash = sourceDigest.digest().joinToString("") {
                        "%02x".format(it)
                    }
                    val (actualHash, actualBytes) = sha256(dest)
                    check(actualHash == expectedHash && actualBytes == count) {
                        "Контрольная сумма пробной копии не совпала"
                    }
                    record.put("sha256", actualHash)
                    writeJournal(appended)
                    val released = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    check(resolver.update(dest, released, null, null) == 1) {
                        "Не удалось опубликовать пробную копию"
                    }
                    record.put("published", true)
                    writeJournal(appended)

                    val dateAdded = resolver.query(dest,
                        arrayOf(MediaStore.MediaColumns.DATE_ADDED), null, null, null)
                        ?.use { c ->
                            if (!c.moveToFirst()) null else
                                c.getLong(c.getColumnIndexOrThrow(
                                    MediaStore.MediaColumns.DATE_ADDED))
                        } ?: error("Невозможно подтвердить дату добавления копии")
                    newAdded.add(dateAdded)
                    onProgress(index + 1, 3)
                } catch (cancel: CancellationException) {
                    // Journal and original source remain intact; cleanup is a
                    // separate explicit operation, never automated at startup.
                    throw cancel
                } catch (error: Exception) {
                    return@withContext Outcome(index + 1,
                        "Проба остановилась: " + (error.message ?: "ошибка") +
                        ". Оригиналы нетронуты. Пробные файлы можно удалить в настройках.")
                }
            }
            val ascending = RepublishTrialPolicy.strictlyIncreasing(newAdded)
            Outcome(3, if (ascending)
                "Созданы 3 проверенные копии от старой к новой; DATE_ADDED строго возрастает. " +
                "Найди отдельный альбом MediaLoader_QA_Order_Test в Xiaomi Gallery. " +
                "Сверху должна быть 03_NEW, ниже 02_MIDDLE, затем 01_OLD. " +
                "Все оригиналы сохранены, но исторические даты НЕ восстановлены."
                else
                "Созданы 3 проверенные копии, но Android не подтвердил последовательность " +
                "DATE_ADDED. Не запускайте массовую пересборку. Пробные копии можно удалить.")
        }

    /** Undo only our uniquely named test copies; NEVER touch sources. */
    suspend fun removeTrialCopies(): Outcome = withContext(Dispatchers.IO) {
        val entries = loadJournal()
        if (entries.length() == 0) {
            return@withContext Outcome(0, "Пробных копий нет.")
        }
        val retained = JSONArray()
        var removed = 0
        for (i in 0 until entries.length()) {
            currentCoroutineContext().ensureActive()
            val entry = entries.getJSONObject(i)
            val uri = Uri.parse(entry.getString("copyUri"))
            val expectedName = entry.getString("name")
            val verified = try {
                resolver.query(uri, arrayOf(
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.IS_PENDING,
                ), null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use false
                    val owner = c.getString(c.getColumnIndexOrThrow(
                        MediaStore.MediaColumns.OWNER_PACKAGE_NAME))
                    val relative = c.getString(c.getColumnIndexOrThrow(
                        MediaStore.MediaColumns.RELATIVE_PATH))
                    val name = c.getString(c.getColumnIndexOrThrow(
                        MediaStore.MediaColumns.DISPLAY_NAME))
                    val pending = c.getInt(c.getColumnIndexOrThrow(
                        MediaStore.MediaColumns.IS_PENDING))
                    owner == context.packageName && relative == TRIAL_PATH &&
                        name == expectedName &&
                        (pending == 1 || runCatching {
                            val (actualHash, count) = sha256(uri)
                            actualHash == entry.optString("sha256", "#none") &&
                                count == entry.getLong("size")
                        }.getOrDefault(false))
                } ?: false
            } catch (_: Exception) { false }
            if (verified && resolver.delete(uri, null, null) == 1) {
                removed++
            } else {
                retained.put(entry)
            }
        }
        writeJournal(retained)
        Outcome(removed, "Удалено только пробных копий: " + removed +
            ". Осталось требующих проверки: " + retained.length() +
            ". Оригиналы MediaLoader не изменялись.")
    }
}

/** Pure ordering and eligibility checks for CI. */
internal object RepublishTrialPolicy {
    fun eligible(owner: String?, ourPackage: String, path: String?, name: String?,
        expectedName: String, mime: String?, size: Long, addedSeconds: Long,
        historicalMillis: Long): Boolean {
        if (owner != ourPackage || path != MediaStoragePolicy.DEFAULT_PICTURES_PATH ||
            name != expectedName || mime == null || historicalMillis <= 0L ||
            !historicalMillis.toDouble().isFinite() ||
            size !in 1..(24L * 1024 * 1024)) return false
        if (MediaStoragePolicy.kind(mime) != MediaStoragePolicy.Kind.IMAGE) return false
        if (MediaStoragePolicy.legacyMime(MediaType.PHOTO, expectedName, mime)
            ?.equals(mime, ignoreCase = true) != true) return false
        return addedSeconds * 1000L > historicalMillis + 86_400_000L
    }

    fun <T> selectThree(sortedByOldest: List<T>): List<T> {
        if (sortedByOldest.size < 3) return emptyList()
        return listOf(sortedByOldest.first(),
            sortedByOldest[sortedByOldest.size / 2], sortedByOldest.last())
    }

    fun strictlyIncreasing(datesSeconds: List<Long>): Boolean =
        datesSeconds.size == 3 && datesSeconds[0] > 0 &&
            datesSeconds.zipWithNext().all { (a, b) -> a < b }
}
