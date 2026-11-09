package com.domenota.medialoader.data.storage

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-triggered one-file recovery trial. Never edits EXIF, MediaStore dates,
 * Room history, image/video bytes, private files or arbitrary phone media.
 * External Gallery might still sort by read-only MediaStore.DATE_ADDED.
 */
internal class MediaDateRecovery(private val context: Context, private val dao: DownloadDao) {
    data class Candidate(val id: String, val uri: String, val name: String,
        val original: Long, val modified: Long, val added: Long, val bytes: Long,
        val mime: String, val path: String)
    data class Outcome(val eligible: Int, val changed: Boolean, val message: String)

    private fun query(row: DownloadEntity): Candidate? {
        if (Build.VERSION.SDK_INT < 29 || row.isPrivate || row.hidden ||
            row.state != DownloadState.COMPLETED || !MediaDateRepairPolicy.validTime(
                row.createdAtEpochMillis, System.currentTimeMillis())) return null
        val uri = row.savedUri?.let(Uri::parse) ?: return null
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return null
        return try {
            context.contentResolver.query(uri, arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED,
            ), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                fun str(k: String) = cursor.getString(cursor.getColumnIndexOrThrow(k))
                fun num(k: String) = cursor.getLong(cursor.getColumnIndexOrThrow(k))
                val path = str(MediaStore.MediaColumns.RELATIVE_PATH)
                val name = str(MediaStore.MediaColumns.DISPLAY_NAME)
                val mime = str(MediaStore.MediaColumns.MIME_TYPE)
                val added = num(MediaStore.MediaColumns.DATE_ADDED) * 1000L
                if (!MediaDateRepairPolicy.isCandidate(str(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                    context.packageName, name, row.originalName, path, mime, row.mediaType,
                    added, row.createdAtEpochMillis)) return@use null
                val physical = str(MediaStore.MediaColumns.DATA)?.let(::File)?.canonicalFile
                    ?: return@use null
                val root = android.os.Environment.getExternalStorageDirectory()
                    .canonicalPath.trimEnd('/') + "/"
                val bytes = num(MediaStore.MediaColumns.SIZE)
                if (bytes <= 0 || !physical.path.startsWith(root) ||
                    !physical.path.contains("/" + path) || !physical.isFile ||
                    !physical.canRead() || physical.length() != bytes) return@use null
                Candidate(row.id, row.savedUri!!, name, row.createdAtEpochMillis,
                    num(MediaStore.MediaColumns.DATE_MODIFIED) * 1000L,
                    added, bytes, mime, physical.path)
            }
        } catch (_: Exception) { null }
    }

    suspend fun preview(): List<Candidate> = withContext(Dispatchers.IO) {
        val rows = dao.all()
        check(rows.none { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }) {
            "Дождитесь завершения загрузок."
        }
        val occurrences = rows.mapNotNull { it.savedUri }.groupingBy { it }.eachCount()
        rows.filter { it.savedUri != null && occurrences[it.savedUri] == 1 }
            .mapNotNull(::query).sortedWith(compareBy({ it.original }, { it.id }))
    }

    private fun digest(uri: String): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val length = stream.read(buffer)
                if (length == -1) break
                sha.update(buffer, 0, length)
            }
        } ?: error("Файл недоступен для контрольной проверки")
        return sha.digest()
    }

    private fun logBefore(c: Candidate, sha: ByteArray) {
        val file = AtomicFile(File(context.noBackupFilesDir, "media-date-recovery-preimages.json"))
        val entries = try { JSONArray(file.openRead().bufferedReader().use { it.readText() }) }
            catch (ex: java.io.FileNotFoundException) { JSONArray() }
        entries.put(JSONObject().put("id", c.id).put("uri", c.uri).put("path", c.path)
            .put("oldModifiedMillis", c.modified).put("newModifiedMillis", c.original)
            .put("dateAddedBeforeMillis", c.added).put("size", c.bytes)
            .put("sha256", sha.joinToString("") { byte -> "%02x".format(byte) }))
        val stream = file.startWrite()
        try {
            stream.write(entries.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    /**
     * Follow-up to a successful one-file trial, only after explicit user
     * confirmation that Xiaomi actually reads the restored timestamp.
     * Neither media bytes nor Room history is rewritten. Every change is
     * individually journaled *before* mutation and verified afterwards.
     */
    suspend fun restoreRemaining(onProgress: (Int, Int) -> Unit): Outcome =
        withContext(Dispatchers.IO) {
            val candidates = preview().filter {
                kotlin.math.abs(File(it.path).lastModified() - it.original) > 2000L
            }
            var changed = 0
            var skipped = 0
            for (candidate in candidates) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val current = dao.byId(candidate.id)
                if (current?.savedUri != candidate.uri || current.isPrivate ||
                    current.hidden || dao.countSavedUriReferences(candidate.uri) != 1 ||
                    query(current) != candidate) {
                    skipped++
                    onProgress(changed + skipped, candidates.size)
                    continue
                }
                val oldHash = try { digest(candidate.uri) } catch (e: Exception) {
                    skipped++
                    onProgress(changed + skipped, candidates.size)
                    continue
                }
                val file = File(candidate.path)
                try {
                    logBefore(candidate, oldHash)
                    if (!file.setLastModified(candidate.original)) {
                        skipped++
                    } else {
                        val identical = file.length() == candidate.bytes &&
                            oldHash.contentEquals(digest(candidate.uri)) &&
                            kotlin.math.abs(file.lastModified() - candidate.original) < 2000L
                        if (!identical) {
                            if (!file.setLastModified(candidate.modified))
                                throw java.io.IOException("Не удалось отменить неуспешную запись даты")
                            skipped++
                        } else {
                            changed++
                            MediaScannerConnection.scanFile(context,
                                arrayOf(candidate.path), arrayOf(candidate.mime), null)
                        }
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (ex: Exception) {
                    // Abort instead of continuing with uncertain filesystem state.
                    file.setLastModified(candidate.modified)
                    return@withContext Outcome(candidates.size, changed > 0,
                        "Остановлено после $changed файлов: " +
                            (ex.message ?: "неизвестная ошибка") +
                            ". Журнал прежних дат сохранён.")
                }
                onProgress(changed + skipped, candidates.size)
            }
            Outcome(candidates.size, changed > 0,
                "Восстановлено времён файлов: $changed, пропущено: $skipped. " +
                    "Файлы и история загрузок не удалялись.")
        }

    /**
     * Recover prior filesystem mtimes from durable journal, in reverse order.
     * This is the exact undo of our own metadata changes, not a file rewrite.
     * Refuse if a file was replaced, modified after trial, or unverified.
     */
    suspend fun undoDateChanges(): Outcome = withContext(Dispatchers.IO) {
        val atomic = AtomicFile(File(context.noBackupFilesDir,
            "media-date-recovery-preimages.json"))
        val records = try {
            JSONArray(atomic.openRead().bufferedReader().use { it.readText() })
        } catch (missing: java.io.FileNotFoundException) {
            return@withContext Outcome(0, false, "Журнал восстановления пуст.")
        }
        var restored = 0
        var skipped = 0
        for (i in records.length() - 1 downTo 0) {
            val record = records.getJSONObject(i)
            if (record.optBoolean("undone", false)) continue
            val id = record.getString("id")
            val uri = record.getString("uri")
            val path = File(record.getString("path")).canonicalFile
            val before = record.getLong("oldModifiedMillis")
            val changed = record.getLong("newModifiedMillis")
            val expectedSize = record.getLong("size")
            val expectedHash = record.getString("sha256")
            val row = dao.byId(id)
            val safe = row?.savedUri == uri && !row.isPrivate && !row.hidden &&
                dao.countSavedUriReferences(uri) == 1 &&
                Uri.parse(uri).authority == MediaStore.AUTHORITY &&
                path.isFile && path.length() == expectedSize &&
                path.canonicalPath.startsWith(android.os.Environment
                    .getExternalStorageDirectory().canonicalPath.trimEnd('/') + "/") &&
                kotlin.math.abs(path.lastModified() - changed) < 2000L &&
                digest(uri).joinToString("") { byte -> "%02x".format(byte) } == expectedHash
            if (!safe || !path.setLastModified(before) ||
                kotlin.math.abs(path.lastModified() - before) > 2000L) {
                skipped++
                continue
            }
            MediaScannerConnection.scanFile(context, arrayOf(path.path),
                arrayOf(null), null)
            record.put("undone", true)
            val output = atomic.startWrite()
            try {
                output.write(records.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (error: Throwable) {
                atomic.failWrite(output)
                throw error
            }
            restored++
        }
        Outcome(records.length(), restored > 0, "Возвращены предыдущие даты: " +
            restored + "; пропущено как небезопасное: " + skipped + ".")
    }

    /** For QA: test the oldest one only; proceed to bulk only after device verification. */
    suspend fun trialOne(): Outcome = withContext(Dispatchers.IO) {
        val candidates = preview()
        val c = candidates.firstOrNull() ?: return@withContext Outcome(0, false,
            "Подходящих файлов нет. Существующие данные не изменены.")
        val latest = dao.byId(c.id)
        if (latest?.savedUri != c.uri || latest.isPrivate || latest.hidden ||
            dao.countSavedUriReferences(c.uri) != 1 || query(latest) != c) {
            return@withContext Outcome(candidates.size, false,
                "Медиафайл изменился во время проверки. Запись пропущена.")
        }
        val file = File(c.path)
        val hash = try { digest(c.uri) } catch (ex: Exception) {
            return@withContext Outcome(candidates.size, false,
                "Не удалось проверить исходные байты. Запись пропущена.")
        }
        logBefore(c, hash)
        if (!file.setLastModified(c.original)) return@withContext Outcome(
            candidates.size, false, "Android запретил изменить время. Файл не перезаписан.")
        val verified = kotlin.math.abs(file.lastModified() - c.original) < 2000L &&
            file.length() == c.bytes &&
            try { hash.contentEquals(digest(c.uri)) } catch (ex: Exception) { false }
        if (!verified) {
            file.setLastModified(c.modified)
            return@withContext Outcome(candidates.size, false,
                "Контроль времени/байтов не прошёл. Прежняя дата возвращена.")
        }
        MediaScannerConnection.scanFile(context, arrayOf(c.path), arrayOf(c.mime), null)
        Outcome(candidates.size, true,
            "Проба выполнена для: " + c.name + ". Проверьте его дату и положение в галерее Xiaomi.")
    }
}

/** Testable fail-closed rules; never infer files by name alone. */
internal object MediaDateRepairPolicy {
    fun validTime(original: Long, now: Long) = original in 946684800000L..now
    fun isCandidate(owner: String?, app: String, name: String?, originalName: String,
        relative: String?, mime: String?, type: MediaType, added: Long, original: Long): Boolean {
        if (owner != app || name != originalName || mime == null || relative == null ||
            relative.contains("..") || relative.contains('\\') ||
            !(relative.startsWith("Pictures/MediaLoader/") ||
                relative.startsWith("Music/MediaLoader/"))) return false
        val required = when (type) {
            MediaType.PHOTO -> MediaStoragePolicy.Kind.IMAGE
            MediaType.VIDEO -> MediaStoragePolicy.Kind.VIDEO
            MediaType.AUDIO -> MediaStoragePolicy.Kind.AUDIO
        }
        return MediaStoragePolicy.kind(mime) == required &&
            validTime(original, System.currentTimeMillis()) && added > original + 86_400_000L
    }
}
