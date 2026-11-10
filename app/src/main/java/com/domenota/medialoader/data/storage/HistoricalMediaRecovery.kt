package com.domenota.medialoader.data.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.room.withTransaction
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

internal data class HistoricalRecoveryItem(
    val downloadId: String,
    val name: String,
    val mime: String,
    val historicalMillis: Long,
    val bytes: Long,
)

internal data class HistoricalRecoveryState(
    val items: List<HistoricalRecoveryItem>,
    val trialPassed: Boolean,
    val pending: Boolean,
    val message: String,
    val canCancel: Boolean = false,
)

/**
 * Explicitly launched, sequential replacement of app-owned public media.
 * The journal is the authority after interruption. Unknown state always stops;
 * neither a failed batch nor an app restart starts another item automatically.
 * Backups are deliberately retained in noBackupFilesDir after successful deletion.
 * A caller must exclude production mutations for the duration of a run; ensureIdle
 * additionally checks that precondition at every durable stage.
 */
internal class HistoricalMediaRecovery(ctx: Context, private val db: HistoryDatabase) {
    private val context = ctx.applicationContext
    private val resolver get() = context.contentResolver
    private val dao get() = db.downloadDao()
    private val links = HistoricalReferenceLinks(context, db)
    private val codec = HistoricalDatedCopyCodec()
    private val root = File(context.noBackupFilesDir, "historical-media-recovery-v1")

    private enum class Stage {
        PREPARED, BACKUP_VERIFIED, DATED_VERIFIED, RENAME_INTENT, QUARANTINED,
        INSERT_INTENT, NEW_RESERVED, NEW_WRITTEN, PUBLICATION_INTENT, PUBLISHED,
        MAIN_INTENT, MAIN_REBOUND, LINKS_REBOUND, DELETE_INTENT, OLD_DELETED, DONE,
    }

    private data class Media(
        val uri: Uri, val owner: String?, val path: String?, val name: String?,
        val mime: String?, val bytes: Long, val pending: Boolean,
        val trashed: Boolean, val taken: Long, val added: Long, val modified: Long,
    )

    companion object {
        private val processLock = Mutex()
        private const val PATH = "Pictures/MediaLoader/"
        private const val RESERVE = 256L * 1024 * 1024
        private const val VERSION = 1
        private const val JOURNAL = "operation.json"
        private const val RECEIPT = "trial-receipt.json"
    }

    suspend fun inspect(): HistoricalRecoveryState = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext HistoricalRecoveryState(
            emptyList(), false, false, "Для восстановления нужен Android 10 или новее")
        val batch = readOrNull(JOURNAL)
        val pending = batch != null && !batch.getBoolean("done")
        val receipt = trialPassed()
        if (pending) return@withContext HistoricalRecoveryState(emptyList(), receipt, true,
            "Есть незавершённое восстановление: ${batch!!.optString("message", "нажмите «Продолжить»")}",
            canCancel = beforePublicMutation(batch))
        links.ensureIdle()
        val rows = dao.all()
        // Listing is only a preview. Read the history once instead of rescanning
        // every database and preference document for every candidate (O(N²)).
        // Count a row once, matching the DAO's savedUri OR privateSourceUri rule.
        val referenceCounts = rows.flatMap { listOfNotNull(it.savedUri, it.privateSourceUri).distinct() }
            .groupingBy { it }.eachCount()
        val items = ArrayList<HistoricalRecoveryItem>()
        var skipped = 0
        for (row in rows) {
            coroutineContext.ensureActive()
            if (!basicCandidate(row)) continue
            try {
                val media = eligible(row, referenceCounts)
                // Full file/name/reference validation and a fresh durable snapshot
                // remain in prepare(), before any original can be changed.
                items += HistoricalRecoveryItem(row.id, row.originalName, media.mime!!,
                    row.createdAtEpochMillis, row.sizeBytes!!)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { skipped++ }
        }
        HistoricalRecoveryState(items, receipt, false,
            "Предварительно подходящих файлов: ${items.size}. Пропущено: $skipped. " +
                if (receipt) "Проба подтверждена; массовый запуск доступен вручную."
                else "Сначала выполните пробу на одном файле.")
    }

    suspend fun run(ids: List<String>, trial: Boolean, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.IO) { exclusive {
            requireSupported()
            val existing = readOrNull(JOURNAL)
            requireSafe(existing == null || existing.getBoolean("done"),
                "Сначала продолжите незавершённое восстановление")
            requireSafe(ids.isNotEmpty() && ids.distinct().size == ids.size,
                "Список восстановления пуст или содержит повторяющиеся ID")
            requireSafe(!trial || ids.size == 1, "Проба допускает ровно один файл")
            requireSafe(trial || trialPassed(), "Сначала нужна успешно завершённая проба")
            links.ensureIdle()
            // Validate every selected identity before recording a batch. No public writes here.
            ids.forEach { id -> eligible(dao.byId(id) ?: fail("Файл $id исчез из истории")) }
            if (existing != null) write("${existing.getString("batch")}-finished.json", existing)
            val batch = JSONObject().put("version", VERSION).put("batch", UUID.randomUUID().toString())
                .put("ids", JSONArray(ids)).put("cursor", 0).put("trial", trial)
                .put("done", false).put("message", "Подготовлено; файлы ещё не менялись")
            write(JOURNAL, batch)
            continueBatch(batch, onProgress)
        } }

    suspend fun resume(onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) { exclusive {
        requireSupported()
        val batch = readOrNull(JOURNAL) ?: return@exclusive "Нет незавершённого восстановления"
        validateBatch(batch)
        if (batch.getBoolean("done")) return@exclusive batch.getString("message")
        continueBatch(batch, onProgress)
    } }

    private suspend fun <T> exclusive(block: suspend () -> T): T = processLock.withLock {
        requireSafe(root.isDirectory || root.mkdirs(), "Не удалось открыть папку журнала восстановления")
        RandomAccessFile(File(root, "operation.lock"), "rw").use { file ->
            val lock = file.channel.tryLock() ?: fail("Восстановление уже выполняется другим процессом")
            try { block() } finally { lock.release() }
        }
    }

    suspend fun cancelBeforePublicMutation(): String = withContext(Dispatchers.IO) { exclusive {
        val batch = readOrNull(JOURNAL) ?: fail("Нет незавершённой операции")
        validateBatch(batch)
        requireSafe(!batch.getBoolean("done") && beforePublicMutation(batch),
            "Начата замена публичного файла: используйте продолжение по журналу")
        val result = "Оставшаяся очередь отменена. Уже завершённые замены сохранены; текущий оригинал не менялся."
        batch.put("done", true).put("cancelled", true).put("message", result)
        write(JOURNAL, batch)
        result
    } }

    private fun beforePublicMutation(batch: JSONObject): Boolean {
        if (!batch.has("operation") || batch.isNull("operation")) return true
        val operation = batch.getJSONObject("operation")
        return operation.getString("stage") in setOf(Stage.PREPARED.name, Stage.BACKUP_VERIFIED.name, Stage.DATED_VERIFIED.name)
    }

    private fun requireSupported() = requireSafe(Build.VERSION.SDK_INT >= 29,
        "Для восстановления нужен Android 10 или новее")

    private fun validateBatch(batch: JSONObject) {
        requireSafe(batch.getInt("version") == VERSION, "Неизвестная версия журнала восстановления")
        uuid(batch.getString("batch"))
        val ids = batch.getJSONArray("ids")
        requireSafe(ids.length() > 0 && batch.getInt("cursor") in 0..ids.length(), "Повреждён список восстановления")
        val selected = (0 until ids.length()).map { ids.getString(it) }
        requireSafe(selected.distinct().size == selected.size && selected.none { it.isBlank() },
            "Повреждены ID в журнале восстановления")
        requireSafe(!batch.getBoolean("trial") || ids.length() == 1, "Повреждена запись одиночной пробы")
        requireSafe(batch.getBoolean("trial") || trialPassed(), "Нет подтверждения успешной пробы")
    }

    private suspend fun continueBatch(batch: JSONObject, onProgress: (String) -> Unit): String {
        validateBatch(batch)
        try {
            val ids = batch.getJSONArray("ids")
            while (batch.getInt("cursor") < ids.length()) {
                coroutineContext.ensureActive()
                links.ensureIdle()
                val cursor = batch.getInt("cursor")
                val id = ids.getString(cursor)
                val operation = batch.optJSONObject("operation") ?: prepare(id).also {
                    batch.put("operation", it)
                    write(JOURNAL, batch)
                }
                requireSafe(operation.getString("downloadId") == id, "ID текущего файла не совпал с журналом")
                advance(batch, operation) { stage ->
                    onProgress("${cursor + 1}/${ids.length()} · ${operation.getString("name")} · $stage")
                }
                requireSafe(operation.getString("stage") == Stage.DONE.name, "Файл не прошёл финальную проверку")
                // One durable checkpoint advances the cursor and removes the completed active op.
                write("${operation.getString("op")}-done.json", operation)
                batch.put("cursor", cursor + 1).remove("operation")
                batch.put("message", "Завершено файлов: ${cursor + 1}/${ids.length()}")
                write(JOURNAL, batch)
                // Staging is reproducible; the original verified backup stays permanently.
                // This follows the durable cursor so interruption cannot remove a needed stage.
                privateFile(operation, "dated").delete()
            }
            // Crash before receipt simply resumes here; receipt is never issued for a partial trial.
            if (batch.getBoolean("trial")) {
                write(RECEIPT, JSONObject().put("version", VERSION).put("batch", batch.getString("batch"))
                    .put("completed", System.currentTimeMillis()).put("done", true))
            }
            val result = "Восстановление завершено: ${ids.length()}. Исходные имена и ID истории сохранены. " +
                "Новые файлы, даты и ссылки проверены; старые публичные файлы удалены. Резервные копии сохранены."
            batch.put("done", true).put("message", result)
            write(JOURNAL, batch)
            return result
        } catch (cancelled: CancellationException) {
            // The preceding durable intent is sufficient to resume; cancellation cannot trigger next file.
            throw cancelled
        } catch (error: Exception) {
            val active = batch.optJSONObject("operation")
            val result = "Остановлено: ${active?.optString("name") ?: "подготовка"}; этап " +
                "${active?.optString("stage") ?: "выбор файла"}. ${error.message ?: error.javaClass.simpleName}. " +
                "Журнал и резерв сохранены. Продолжение запускается вручную."
            batch.put("message", result)
            write(JOURNAL, batch)
            onProgress(result)
            return result
        }
    }

    private suspend fun prepare(id: String): JSONObject {
        val row = dao.byId(id) ?: fail("Файл отсутствует в истории")
        val media = eligible(row)
        ensureSpace(row.sizeBytes!!, initial = true)
        val oldHash = hash(media.uri)
        requireSafe(dao.byId(id) == row, "История изменилась во время подготовки")
        val snapshot = links.snapshot(row)
        val op = UUID.randomUUID().toString()
        val ext = if (media.mime == "image/jpeg") "jpg" else "mp4"
        return JSONObject().put("version", VERSION).put("op", op).put("downloadId", id)
            .put("name", row.originalName).put("mime", media.mime).put("path", media.path)
            .put("historical", row.createdAtEpochMillis).put("oldUri", media.uri.toString())
            .put("oldBytes", row.sizeBytes).put("oldHash", oldHash)
            .put("quarantine", "ML_recovery_${op}_original.$ext")
            .put("extension", ext).put("row", rowJson(row)).put("links", snapshot)
            .put("stage", Stage.PREPARED.name)
    }

    private suspend fun advance(batch: JSONObject, op: JSONObject, progress: (String) -> Unit) {
        validateOperation(op)
        val backup = privateFile(op, "backup")
        val dated = privateFile(op, "dated")
        while (true) {
            coroutineContext.ensureActive()
            links.ensureIdle()
            val stage = runCatching { Stage.valueOf(op.getString("stage")) }
                .getOrElse { fail("Неизвестный этап в журнале восстановления") }
            progress(stageLabel(stage))
            when (stage) {
                Stage.PREPARED -> {
                    verifyMain(op, forward = false)
                    original(op, allowOriginalName = true)
                    ensureSpace(op.getLong("oldBytes"), initial = true)
                    if (!backup.exists()) copyBackup(op, backup)
                    verifyBackup(op, backup)
                    original(op, allowOriginalName = true)
                    checkpoint(batch, op, Stage.BACKUP_VERIFIED)
                }
                Stage.BACKUP_VERIFIED -> {
                    verifyMain(op, false); verifyBackup(op, backup)
                    original(op, allowOriginalName = true)
                    ensureSpace(op.getLong("oldBytes"), initial = false)
                    if (!op.has("newHash")) {
                        codec.build(backup, dated, op.getString("mime"), op.getLong("historical"))
                        sync(dated)
                        requireSafe(dated.length() > 0, "Созданная копия пуста")
                        codec.verify(backup, dated, op.getString("mime"))
                        op.put("newHash", hash(dated)).put("newBytes", dated.length())
                            .put("contentVerified", true)
                    }
                    verifyDated(op, backup, dated)
                    checkpoint(batch, op, Stage.DATED_VERIFIED)
                }
                Stage.DATED_VERIFIED -> {
                    verifyMain(op, false); original(op, allowOriginalName = true)
                    verifyDated(op, backup, dated)
                    requireSafe(find(collection(op), op.getString("quarantine")).isEmpty(),
                        "Уникальное резервное имя уже занято")
                    checkpoint(batch, op, Stage.RENAME_INTENT)
                }
                Stage.RENAME_INTENT -> {
                    verifyMain(op, false); verifyBackup(op, backup)
                    val old = original(op, allowOriginalName = true)
                    if (old.name == op.getString("name")) {
                        requireSafe(find(collection(op), op.getString("quarantine")).isEmpty(),
                            "Резервное имя стало занято")
                        requireSafe(resolver.update(old.uri, ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, op.getString("quarantine"))
                        }, null, null) == 1, "Android не переименовал исходный файл")
                    }
                    original(op, allowOriginalName = false)
                    checkpoint(batch, op, Stage.QUARANTINED)
                }
                Stage.QUARANTINED -> {
                    verifyMain(op, false); original(op, allowOriginalName = false)
                    requireSafe(find(collection(op), op.getString("name")).isEmpty(),
                        "Исходное имя занято другим файлом; копия не создавалась")
                    checkpoint(batch, op, Stage.INSERT_INTENT)
                }
                Stage.INSERT_INTENT -> {
                    verifyMain(op, false); original(op, allowOriginalName = false)
                    verifyDated(op, backup, dated)
                    val found = find(collection(op), op.getString("name"))
                    requireSafe(found.size <= 1, "Несколько записей с исходным именем")
                    // Intent precedes insert. Pending-inclusive lookup recovers insert-before-journal crashes.
                    val uri = if (op.has("newUri")) Uri.parse(op.getString("newUri"))
                    else if (found.isNotEmpty()) found.single() else {
                        ensureSpace(op.getLong("newBytes"), initial = false)
                        resolver.insert(collection(op), ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, op.getString("name"))
                            put(MediaStore.MediaColumns.RELATIVE_PATH, PATH)
                            put(MediaStore.MediaColumns.MIME_TYPE, op.getString("mime"))
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }) ?: fail("Android не создал новую запись")
                    }
                    op.put("newUri", uri.toString())
                    // Persist returned URI before identity checking or opening a write descriptor.
                    write(JOURNAL, batch)
                    val fresh = identity(op, uri, op.getString("name"))
                    requireSafe(fresh.pending, "Найденная новая запись уже опубликована; запись остановлена")
                    checkpoint(batch, op, Stage.NEW_RESERVED)
                }
                Stage.NEW_RESERVED -> {
                    verifyMain(op, false); original(op, allowOriginalName = false)
                    verifyDated(op, backup, dated)
                    val uri = newUri(op)
                    val media = identity(op, uri, op.getString("name"))
                    requireSafe(media.pending, "Новая запись опубликована до завершения записи")
                    ensureSpace(op.getLong("newBytes"), initial = false)
                    resolver.openFileDescriptor(uri, "rwt")?.use { fd ->
                        FileOutputStream(fd.fileDescriptor).use { out ->
                            dated.inputStream().use { copy(it, out) }; out.flush(); out.fd.sync()
                        }
                    } ?: fail("Не удалось открыть новую запись для записи")
                    verifyNew(op, published = false, verifyDate = false)
                    checkpoint(batch, op, Stage.NEW_WRITTEN)
                }
                Stage.NEW_WRITTEN -> {
                    verifyMain(op, false); original(op, allowOriginalName = false)
                    verifyDated(op, backup, dated)
                    verifyNew(op, published = false, verifyDate = false)
                    checkpoint(batch, op, Stage.PUBLICATION_INTENT)
                }
                Stage.PUBLICATION_INTENT -> {
                    verifyMain(op, false); original(op, allowOriginalName = false)
                    val media = identity(op, newUri(op), op.getString("name"))
                    if (media.pending) {
                        verifyNew(op, published = false, verifyDate = false)
                        setHistoricalMtime(op)
                        requireSafe(resolver.update(newUri(op), ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }, null, null) == 1, "Android не опубликовал проверенную копию")
                    }
                    verifyPublishedWithRetry(op)
                    checkpoint(batch, op, Stage.PUBLISHED)
                }
                Stage.PUBLISHED -> {
                    verifyMain(op, false); original(op, allowOriginalName = false)
                    verifyBackup(op, backup); verifyNew(op, published = true, verifyDate = true)
                    checkpoint(batch, op, Stage.MAIN_INTENT)
                }
                Stage.MAIN_INTENT -> {
                    original(op, allowOriginalName = false)
                    verifyNew(op, published = true, verifyDate = true)
                    db.withTransaction {
                        val row = dao.byId(op.getString("downloadId")) ?: fail("Запись истории исчезла")
                        if (row.savedUri == op.getString("oldUri")) {
                            verifyMain(op, false)
                            requireSafe(dao.rebindSavedFileWhenCurrent(row.id, op.getString("oldUri"),
                                op.getString("name"), op.getLong("oldBytes"), op.getString("newUri"),
                                op.getString("name"), op.getLong("newBytes")) == 1,
                                "История изменилась; переключение отменено")
                        }
                        verifyMain(op, true)
                    }
                    checkpoint(batch, op, Stage.MAIN_REBOUND)
                }
                Stage.MAIN_REBOUND -> {
                    verifyMain(op, true); original(op, allowOriginalName = false)
                    verifyNew(op, published = true, verifyDate = true)
                    links.rebind(op.getJSONObject("links"), op.getString("oldUri"), op.getString("newUri"),
                        op.getString("oldHash"), op.getString("newHash"), op.getString("name"), op.getLong("newBytes"))
                    links.verify(op.getJSONObject("links"), op.getString("newUri"), op.getString("newHash"))
                    checkpoint(batch, op, Stage.LINKS_REBOUND)
                }
                Stage.LINKS_REBOUND -> {
                    verifyFinalLinks(op)
                    original(op, allowOriginalName = false); verifyBackup(op, backup)
                    requireSafe(dao.countSavedUriReferences(op.getString("oldUri")) == 0,
                        "Другие строки истории всё ещё ссылаются на исходный файл")
                    checkpoint(batch, op, Stage.DELETE_INTENT)
                }
                Stage.DELETE_INTENT -> {
                    // Intent is durable before delete. Missing ONLY at this stage means a prior delete completed.
                    verifyFinalLinks(op); verifyBackup(op, backup)
                    requireSafe(dao.countSavedUriReferences(op.getString("oldUri")) == 0,
                        "Появилась ссылка истории на исходный файл; удаление остановлено")
                    val old = media(Uri.parse(op.getString("oldUri")))
                    if (old != null) {
                        original(op, allowOriginalName = false)
                        requireSafe(resolver.delete(old.uri, null, null) == 1,
                            "Android не удалил старый публичный файл")
                    }
                    requireSafe(media(Uri.parse(op.getString("oldUri"))) == null,
                        "Старая публичная запись осталась после удаления")
                    checkpoint(batch, op, Stage.OLD_DELETED)
                }
                Stage.OLD_DELETED -> {
                    verifyFinalLinks(op); verifyBackup(op, backup)
                    requireSafe(media(Uri.parse(op.getString("oldUri"))) == null,
                        "Исходная запись снова появилась; нужна проверка")
                    checkpoint(batch, op, Stage.DONE)
                }
                Stage.DONE -> {
                    verifyFinalLinks(op); verifyBackup(op, backup)
                    requireSafe(media(Uri.parse(op.getString("oldUri"))) == null,
                        "Исходная запись снова появилась после завершения")
                    return
                }
            }
        }
    }

    private fun checkpoint(batch: JSONObject, op: JSONObject, stage: Stage) {
        op.put("stage", stage.name)
        batch.put("message", "${op.getString("name")}: ${stageLabel(stage)}")
        write(JOURNAL, batch)
    }

    private fun validateOperation(op: JSONObject) {
        val id = op.getString("op"); uuid(id)
        requireSafe(op.getInt("version") == VERSION && op.getString("path") == PATH,
            "Журнал указывает неподдерживаемую папку")
        val row = op.getJSONObject("row")
        requireSafe(row.getString("id") == op.getString("downloadId") &&
            row.getString("savedUri") == op.getString("oldUri") &&
            row.getString("originalName") == op.getString("name") &&
            row.getLong("sizeBytes") == op.getLong("oldBytes") &&
            row.getLong("createdAtEpochMillis") == op.getLong("historical") &&
            row.getString("state") == DownloadState.COMPLETED.name &&
            !row.getBoolean("isPrivate") && !row.getBoolean("hidden") && row.isNull("privateSourceUri"),
            "Данные операции не совпадают с исходным снимком истории")
        val name = op.getString("name")
        requireSafe(name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }, "Недопустимое имя в журнале")
        requireSafe(op.getString("quarantine") == "ML_recovery_${id}_original.${op.getString("extension")}",
            "Резервное имя не принадлежит этой операции")
        requireSafe(validUri(Uri.parse(op.getString("oldUri")), collection(op)) &&
            op.getLong("oldBytes") > 0 && op.getLong("historical") > 0 &&
            op.getString("oldHash").matches(Regex("[0-9a-f]{64}")), "Повреждена идентичность исходного файла")
        if (op.has("newUri")) requireSafe(validUri(newUri(op), collection(op)) &&
            op.getString("newUri") != op.getString("oldUri"), "Повреждён URI новой копии")
        if (op.has("newHash")) requireSafe(op.getLong("newBytes") > 0 &&
            op.getString("newHash").matches(Regex("[0-9a-f]{64}")) && op.getBoolean("contentVerified"),
            "Повреждено подтверждение проверки новой копии")
    }

    private fun basicCandidate(row: DownloadEntity): Boolean = row.state == DownloadState.COMPLETED &&
        !row.hidden && !row.isPrivate && row.privateSourceUri == null &&
        row.mediaType in setOf(MediaType.PHOTO, MediaType.VIDEO) && row.createdAtEpochMillis > 0 &&
        row.createdAtEpochMillis <= System.currentTimeMillis() && row.sizeBytes != null && row.sizeBytes > 0 &&
        !row.savedUri.isNullOrBlank() && row.originalName.isNotBlank() &&
        row.originalName != "." && row.originalName != ".." &&
        row.originalName.none { it == '/' || it == '\\' || it.isISOControl() }

    private suspend fun eligible(row: DownloadEntity, previewReferences: Map<String, Int>? = null): Media {
        requireSafe(basicCandidate(row), "Файл не является завершённым обычным публичным фото или видео")
        val references = if (previewReferences == null) dao.countSavedUriReferences(row.savedUri!!)
            else previewReferences[row.savedUri] ?: 0
        requireSafe(references == 1,
            "На исходный URI ссылается несколько записей истории")
        val uri = Uri.parse(row.savedUri)
        val current = media(uri) ?: fail("Исходная запись MediaStore отсутствует")
        requireSafe(current.mime == "image/jpeg" && row.mediaType == MediaType.PHOTO ||
            current.mime == "video/mp4" && row.mediaType == MediaType.VIDEO,
            "Поддерживаются только JPEG и MP4 соответствующего типа")
        requireSafe(validUri(uri, collection(current.mime!!)), "URI не относится к публичной коллекции MediaStore")
        requireSafe(current.owner == context.packageName && current.path == PATH &&
            current.name == row.originalName && current.bytes == row.sizeBytes && !current.pending && !current.trashed,
            "Владелец, папка, имя, размер или состояние исходного файла не совпали")
        requireSafe(abs(current.taken - row.createdAtEpochMillis) > 1000L,
            "Историческая дата файла уже подтверждена")
        if (previewReferences == null) {
            requireSafe(find(collection(current.mime), row.originalName) == listOf(uri),
                "Имя исходного файла неоднозначно")
            resolver.openFileDescriptor(uri, "r")?.use { fd ->
                requireSafe(fd.statSize == row.sizeBytes, "Фактический размер исходного файла не совпал с историей")
            } ?: fail("Исходный файл недоступен для чтения")
        }
        return current
    }

    private fun validUri(uri: Uri, collection: Uri): Boolean = uri.scheme == "content" &&
        uri.authority == MediaStore.AUTHORITY && uri.toString().startsWith(collection.toString() + "/") &&
        uri.lastPathSegment?.toLongOrNull()?.let { it > 0 } == true &&
        uri.pathSegments.size == collection.pathSegments.size + 1 && uri.query == null && uri.fragment == null

    private fun collection(mime: String): Uri = when (mime) {
        "image/jpeg" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        "video/mp4" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> fail("Неподдерживаемый MIME в журнале")
    }
    private fun collection(op: JSONObject) = collection(op.getString("mime"))
    private fun newUri(op: JSONObject) = Uri.parse(op.getString("newUri"))

    private fun queryUri(uri: Uri): Uri {
        if (Build.VERSION.SDK_INT >= 30) return uri
        @Suppress("DEPRECATION")
        return MediaStore.setIncludePending(uri)
    }

    private fun queryArgs(selection: String? = null, args: Array<String>? = null) = Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        if (args != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
    }

    private fun media(uri: Uri): Media? {
        requireSafe(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY,
            "URI вне MediaStore")
        val columns = mutableListOf("owner_package_name", "relative_path", "_display_name", "mime_type", "_size",
            "is_pending", "datetaken", "date_added", "date_modified")
        if (Build.VERSION.SDK_INT >= 30) columns += "is_trashed"
        val cursor = if (Build.VERSION.SDK_INT >= 30) resolver.query(uri, columns.toTypedArray(), queryArgs(), null)
        else resolver.query(queryUri(uri), columns.toTypedArray(), null, null, null)
        return (cursor ?: fail("MediaStore не вернул курсор проверки")) .use { c ->
            requireSafe(c.count <= 1, "MediaStore вернул несколько строк для одного URI")
            if (!c.moveToFirst()) null else Media(uri, c.getString(0), c.getString(1), c.getString(2),
                c.getString(3), c.getLong(4), c.getInt(5) != 0,
                Build.VERSION.SDK_INT >= 30 && c.getInt(9) != 0, c.getLong(6), c.getLong(7), c.getLong(8))
        }
    }

    private fun find(collection: Uri, name: String): List<Uri> {
        val selection = "relative_path = ? AND _display_name = ?"
        val args = arrayOf(PATH, name)
        val cursor = if (Build.VERSION.SDK_INT >= 30) resolver.query(collection, arrayOf("_id"),
            queryArgs(selection, args), null)
        else resolver.query(queryUri(collection), arrayOf("_id"), selection, args, null)
        return (cursor ?: fail("MediaStore не вернул список имён")).use { c ->
            val result = ArrayList<Uri>()
            while (c.moveToNext()) result += ContentUris.withAppendedId(collection, c.getLong(0))
            result
        }
    }

    private fun identity(op: JSONObject, uri: Uri, name: String): Media {
        requireSafe(validUri(uri, collection(op)), "URI файла не соответствует коллекции журнала")
        val row = media(uri) ?: fail("Запись MediaStore исчезла")
        requireSafe(row.owner == context.packageName && row.path == PATH && row.name == name &&
            row.mime == op.getString("mime") && !row.trashed,
            "Владелец, путь, имя, MIME или корзина файла изменились")
        requireSafe(find(collection(op), name) == listOf(uri), "Имя файла занято или неоднозначно")
        return row
    }

    private suspend fun original(op: JSONObject, allowOriginalName: Boolean): Media {
        val uri = Uri.parse(op.getString("oldUri"))
        val row = media(uri) ?: fail("Исходный публичный файл отсутствует до разрешённого удаления")
        val allowed = row.name == op.getString("quarantine") ||
            allowOriginalName && row.name == op.getString("name")
        requireSafe(allowed, "Имя исходного файла изменилось вне операции")
        val result = identity(op, uri, row.name!!)
        requireSafe(!result.pending && result.bytes == op.getLong("oldBytes"),
            "Размер или публикация исходного файла изменились")
        requireSafe(hash(uri) == op.getString("oldHash"), "Исходный файл изменился по SHA-256")
        return result
    }

    private suspend fun verifyNew(op: JSONObject, published: Boolean, verifyDate: Boolean) {
        val uri = newUri(op)
        requireSafe(uri.toString() != op.getString("oldUri"), "Новая запись совпала с исходной")
        val row = identity(op, uri, op.getString("name"))
        requireSafe(row.pending != published, "Состояние публикации новой копии не совпало")
        val expectedBytes = op.getLong("newBytes")
        // Pending MediaStore SIZE can stay zero until publication/scanning. It
        // is not the file size. Verify the reopened descriptor AND the actual
        // bytes read below; final commits still require confirmed index data.
        if (published && verifyDate) requireSafe(row.bytes == expectedBytes,
            "Индекс размера новой копии ещё не подтверждён: ${row.bytes}, ожидается $expectedBytes")
        resolver.openFileDescriptor(uri, "r")?.use { fd ->
            val actual = fd.statSize
            requireSafe(actual == -1L || actual == expectedBytes,
                "Фактический размер новой копии: $actual, ожидается $expectedBytes")
        } ?: fail("Новая копия не открывается повторно")
        val actualHash = resolver.openInputStream(uri)?.use { hash(it, expectedBytes) }
            ?: fail("Новая копия не открывается для SHA-256")
        requireSafe(actualHash == op.getString("newHash"), "SHA-256 новой копии не совпал с проверенным файлом")
        if (verifyDate) requireSafe(abs(row.taken - op.getLong("historical")) <= 1000L,
            "Android не подтвердил историческую дату новой копии")
    }

    private suspend fun verifyPublishedWithRetry(op: JSONObject) {
        // Hash once per verification; scanner polling reads only bounded metadata rows.
        verifyNew(op, published = true, verifyDate = false)
        repeat(12) { attempt ->
            val row = identity(op, newUri(op), op.getString("name"))
            requireSafe(!row.pending, "Публикация копии изменилась")
            if (row.bytes == op.getLong("newBytes") &&
                abs(row.taken - op.getLong("historical")) <= 1000L) return
            if (attempt != 11) delay(400)
        }
        fail("Android ещё не подтвердил размер и историческую дату копии; исходный файл и журнал сохранены")
    }

    @Suppress("DEPRECATION")
    private fun setHistoricalMtime(op: JSONObject) {
        val uri = newUri(op)
        requireSafe(identity(op, uri, op.getString("name")).pending,
            "Изменять дату можно только до первой публикации")
        val file = resolver.query(queryUri(uri), arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { c ->
                requireSafe(c.count == 1 && c.moveToFirst() && !c.isNull(0), "Нет пути новой pending-копии")
                File(c.getString(0)).canonicalFile
            } ?: fail("Не удалось прочитать путь новой pending-копии")
        val directory = File(Environment.getExternalStorageDirectory(), PATH).canonicalFile
        requireSafe(file.parentFile == directory && file.name == op.getString("name") &&
            file.length() == op.getLong("newBytes"), "Путь на диске не соответствует новой копии")
        requireSafe(file.setLastModified(op.getLong("historical")), "Не удалось задать историческую дату файла")
        requireSafe(abs(file.lastModified() - op.getLong("historical")) <= 1000,
            "Историческая дата файла не сохранилась перед публикацией")
    }

    private suspend fun verifyFinalLinks(op: JSONObject) {
        verifyMain(op, true)
        verifyNew(op, published = true, verifyDate = true)
        links.verify(op.getJSONObject("links"), op.getString("newUri"), op.getString("newHash"))
    }

    private suspend fun verifyMain(op: JSONObject, forward: Boolean) {
        val row = dao.byId(op.getString("downloadId")) ?: fail("Запись истории отсутствует")
        val expected = JSONObject(op.getJSONObject("row").toString())
        if (forward) expected.put("savedUri", op.getString("newUri"))
            .put("originalName", op.getString("name")).put("sizeBytes", op.getLong("newBytes"))
        val artworkRebound = JSONObject(expected.toString())
        if (forward) for (key in listOf("previewUrl", "sourceAudioArtworkUrl", "recognitionArtworkUrl")) {
            if (!artworkRebound.isNull(key) && artworkRebound.getString(key) == op.getString("oldUri"))
                artworkRebound.put(key, op.getString("newUri"))
        }
        val actual = rowJson(row).toString()
        requireSafe(actual == expected.toString() || (forward && actual == artworkRebound.toString()),
            "Полный снимок истории изменился; ID, источник или другие поля не совпали")
        val uri = if (forward) op.getString("newUri") else op.getString("oldUri")
        requireSafe(dao.countSavedUriReferences(uri) == 1,
            "URI файла стал использоваться несколькими строками истории")
    }

    /** Includes every persisted field, including newly added fields, with stable ordering. */
    private fun rowJson(row: DownloadEntity): JSONObject = JSONObject().also { json ->
        DownloadEntity::class.java.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            .sortedBy { it.name }.forEach { field ->
                field.isAccessible = true
                val value = field.get(row)
                json.put(field.name, when (value) {
                    null -> JSONObject.NULL
                    is Enum<*> -> value.name
                    is String, is Number, is Boolean -> value
                    else -> fail("Неподдерживаемое поле снимка истории: ${field.name}")
                })
            }
    }

    private fun privateFile(op: JSONObject, kind: String): File {
        val id = op.getString("op"); uuid(id)
        val ext = op.getString("extension")
        requireSafe(ext == if (op.getString("mime") == "image/jpeg") "jpg" else "mp4",
            "Расширение журнала не соответствует MIME")
        return File(root, "$id-$kind.$ext")
    }

    private suspend fun copyBackup(op: JSONObject, backup: File) {
        val temporary = File(backup.path + ".partial")
        FileOutputStream(temporary).use { out ->
            resolver.openInputStream(Uri.parse(op.getString("oldUri")))?.use { copy(it, out) }
                ?: fail("Не удалось прочитать исходный файл для резерва")
            out.flush(); out.fd.sync()
        }
        requireSafe(temporary.length() == op.getLong("oldBytes") && hash(temporary) == op.getString("oldHash"),
            "Резервная копия не прошла проверку размера и SHA-256")
        requireSafe(temporary.renameTo(backup), "Не удалось закрепить проверенную резервную копию")
    }

    private suspend fun verifyBackup(op: JSONObject, backup: File) {
        requireSafe(backup.isFile && backup.length() == op.getLong("oldBytes") &&
            hash(backup) == op.getString("oldHash"), "Проверенный резерв отсутствует или изменился")
    }

    private suspend fun verifyDated(op: JSONObject, backup: File, dated: File) {
        verifyBackup(op, backup)
        requireSafe(dated.isFile && dated.length() == op.getLong("newBytes") && hash(dated) == op.getString("newHash"),
            "Проверенная датированная копия отсутствует или изменилась")
        // The durable verification receipt binds the codec's decode/stream checks
        // to these exact bytes. Resuming hashes them again without decoding a large
        // MP4 at every subsequent journal stage.
        requireSafe(op.getBoolean("contentVerified"), "Копия не прошла проверку декодирования и потоков")
    }

    private fun ensureSpace(bytes: Long, initial: Boolean) {
        requireSafe(bytes > 0 && bytes <= Long.MAX_VALUE / 4, "Размер файла вне допустимого диапазона")
        // Keep original public bytes + private backup + private remux + pending public copy.
        // 1.4 GB MP4 is supported; availability, not an arbitrary size cap, is the gate.
        val internalNeeded = bytes * (if (initial) 3 else 2) + RESERVE
        val externalNeeded = bytes * 2 + RESERVE
        requireSafe(StatFs(root.path).availableBytes > internalNeeded &&
            StatFs(Environment.getExternalStorageDirectory().path).availableBytes > externalNeeded,
            "Недостаточно места для резерва и проверяемой копии; исходный файл сохранён")
    }

    private suspend fun hash(file: File): String = file.inputStream().use { hash(it) }
    private suspend fun hash(uri: Uri): String = resolver.openInputStream(uri)?.use { hash(it) }
        ?: fail("Не удалось открыть файл для SHA-256")
    private suspend fun hash(input: InputStream, expectedBytes: Long? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        var bytes = 0L
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            bytes += count
            requireSafe(expectedBytes == null || bytes <= expectedBytes,
                "Фактический размер новой копии превысил ожидаемый: $expectedBytes")
            digest.update(buffer, 0, count)
        }
        requireSafe(expectedBytes == null || bytes == expectedBytes,
            "Фактический размер новой копии: $bytes, ожидается $expectedBytes")
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private suspend fun copy(input: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(128 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }

    private fun sync(file: File) = RandomAccessFile(file, "rw").use { it.fd.sync() }
    private fun uuid(value: String) = requireSafe(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false),
        "Неверный идентификатор операции в журнале")
    private fun trialPassed(): Boolean = readOrNull(RECEIPT)?.let {
        it.getInt("version") == VERSION && it.getBoolean("done") && it.getLong("completed") > 0
    } == true

    private fun readOrNull(name: String): JSONObject? {
        val atomic = AtomicFile(File(root, name))
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        return JSONObject(atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    private fun write(name: String, value: JSONObject) {
        requireSafe(root.isDirectory || root.mkdirs(), "Не удалось создать папку журнала")
        val atomic = AtomicFile(File(root, name))
        val stream = atomic.startWrite()
        try {
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }

    private fun requireSafe(condition: Boolean, message: String) { if (!condition) fail(message) }
    private fun fail(message: String): Nothing = throw IllegalStateException(message)

    private fun stageLabel(stage: Stage): String = when (stage) {
        Stage.PREPARED -> "проверка и резерв исходного файла"
        Stage.BACKUP_VERIFIED -> "создание датированной копии"
        Stage.DATED_VERIFIED, Stage.RENAME_INTENT -> "освобождение исходного имени"
        Stage.QUARANTINED, Stage.INSERT_INTENT, Stage.NEW_RESERVED -> "создание скрытой новой записи"
        Stage.NEW_WRITTEN -> "проверка содержимого перед публикацией"
        Stage.PUBLICATION_INTENT -> "публикация с исторической датой"
        Stage.PUBLISHED, Stage.MAIN_INTENT -> "переключение истории с сохранением ID"
        Stage.MAIN_REBOUND -> "перенос ссылок и индексов"
        Stage.LINKS_REBOUND -> "повторное открытие и проверка всех ссылок"
        Stage.DELETE_INTENT -> "проверка и удаление старого публичного файла"
        Stage.OLD_DELETED, Stage.DONE -> "финальная проверка"
    }
}
