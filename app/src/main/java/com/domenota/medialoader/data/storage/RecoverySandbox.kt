package com.domenota.medialoader.data.storage

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.room.Room
import androidx.room.withTransaction
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.vkcommunity.VkCommunityDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

/**
 * ONLY generated fixtures. No production DAO, repository, user URI or ID can be passed here.
 * All mutation targets are bound to an operation UUID, a private plan and QA MediaStore path.
 * Backups, original rows and replacements are deliberately retained; there is no delete API.
 */
internal class RecoverySandbox(private val context: Context) {
    companion object {
        const val PATH = "Pictures/MediaLoader_QA_Recovery/"
        private const val RESERVE = 128L * 1024 * 1024
        private const val HISTORICAL = 1790812800000L // 2026-10-01 UTC, synthetic queue time
    }
    private val root = File(context.noBackupFilesDir, "recovery-sandbox-v1")
    private val resolver get() = context.contentResolver
    private fun atomic(name: String) = AtomicFile(File(root, name))
    private fun read(name: String): JSONObject = JSONObject(atomic(name).openRead().bufferedReader().use { it.readText() })
    private fun write(name: String, value: JSONObject) {
        val file = atomic(name)
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray()); file.finishWrite(stream) }
        catch (e: Throwable) { file.failWrite(stream); throw e }
    }

    suspend fun execute(action: String): String = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 29) { "Нужен Android 10 или новее" }
        check(root.isDirectory || root.mkdirs())
        RecoveryExclusive.run(File(root, "operation.lock")) { perform(action) }
    }

    private fun perform(action: String): String {
        require(action in setOf("JPEG", "MP4", "RESUME", "ROLLBACK", "STATUS"))
        if (action == "JPEG" || action == "MP4") {
            // Starting another fixture never overwrites an interrupted operation.
            if (File(root, "plan.json").exists()) {
                val previous = read("plan.json")
                val old = read("journal.json")
                check(old.getBoolean("rolledBack")) { "Сначала продолжите или откатите предыдущую пробу" }
                val previousId = previous.getString("operation")
                check(UUID.fromString(previousId).toString() == previousId && old.getString("operation") == previousId)
                // Keep an audit trail after the next fixture becomes the active operation.
                write("$previousId-plan.finished.json", previous)
                write("$previousId-journal.finished.json", old)
            }
            prepare(action)
        }
        val plan = read("plan.json")
        check(plan.getInt("version") == 1 && plan.getBoolean("synthetic"))
        val op = plan.getString("operation")
        check(UUID.fromString(op).toString() == op)
        // Recover interruption between durable new plan and first journal write.
        val prior = if (File(root, "journal.json").exists()) read("journal.json") else null
        if (prior == null || prior.getString("operation") != op) {
            check(!plan.optBoolean("seeded") && (prior == null || prior.getBoolean("rolledBack")))
            write("journal.json", JSONObject().put("version", 1).put("operation", op)
                .put("stage", RecoveryStage.PREPARED.name).put("rollingBack", false).put("rollbackStep", 0).put("rolledBack", false))
        }
        if (action == "STATUS") return "Искусственная проба $op: ${read("journal.json").getString("stage")}. Оригинал и резерв сохраняются."
        val journal = object : RecoveryJournal {
            override fun read(): RecoveryRecord {
                val j = this@RecoverySandbox.read("journal.json")
                check(j.getInt("version") == 1 && j.getString("operation") == op)
                return RecoveryRecord(op, RecoveryStage.valueOf(j.getString("stage")),
                    j.getBoolean("rollingBack"), j.getInt("rollbackStep"), j.getBoolean("rolledBack")).also {
                    check(it.rollbackStep in 0..5)
                }
            }
            override fun write(record: RecoveryRecord) = this@RecoverySandbox.write("journal.json", JSONObject()
                .put("version", 1).put("operation", record.operation).put("stage", record.stage.name)
                .put("rollingBack", record.rollingBack).put("rollbackStep", record.rollbackStep).put("rolledBack", record.rolledBack))
        }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(root, "$op-$name")
        }
        val main = Room.databaseBuilder(context, HistoryDatabase::class.java, File(root, "$op-history.db").path).build()
        val vk = VkCommunityDatabase(isolated)
        try {
            val ports = Ports(plan, main, vk)
            val transaction = RecoveryTransaction(journal, ports)
            val record = if (action == "ROLLBACK") transaction.rollback() else transaction.resume()
            return "${if (record.rolledBack) "Откат проверен" else "Проба завершена"} · ${plan.getString("kind")}\n" +
                "ID истории сохранён; избранное, альбом, группа и источники проверены. " +
                "Room, VK, каталог и плейлист согласованы. SHA-256, декодирование и дата проверены.\n" +
                "Оригинал и резерв оставлены. Имена: fixture → restored (конфликт исключён UUID). " +
                "Массовый и реальный режимы выключены."
        } finally { main.close(); vk.close() }
    }

    private fun prepare(kind: String) {
        check(StatFs(root.path).availableBytes > RESERVE + 32L * 1024 * 1024) { "Недостаточно места; ничего не удалено" }
        val op = UUID.randomUUID().toString()
        val extension = if (kind == "JPEG") "jpg" else "mp4"
        val source = File(root, "$op-fixture.$extension")
        if (kind == "JPEG") {
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(40, 90, 160))
            FileOutputStream(source).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)); it.fd.sync() }
            bitmap.recycle()
            // Establish a canonical EXIF baseline before taking its immutable checksum.
            ExifInterface(source.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                saveAttributes()
            }
            sync(source)
        } else {
            ffmpeg(arrayOf("-y", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=12:duration=2",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-c:v", "mpeg4", "-c:a", "aac", "-shortest", source.path))
            sync(source)
        }
        val digest = hash(source)
        val plan = JSONObject().put("version", 1).put("synthetic", true).put("operation", op)
            .put("kind", kind).put("extension", extension).put("historical", HISTORICAL)
            .put("originalSha", digest).put("bytes", source.length())
        // The plan precedes MediaStore insertion, allowing deterministic orphan discovery.
        write("plan.json", plan)
        write("journal.json", JSONObject().put("version", 1).put("operation", op)
            .put("stage", RecoveryStage.PREPARED.name).put("rollingBack", false).put("rollbackStep", 0).put("rolledBack", false))
        // Remaining preparation is idempotent and runs from verifyIdentityAndBackup on resume.
    }

    private inner class Ports(val plan: JSONObject, val main: HistoryDatabase, val vk: VkCommunityDatabase) : RecoveryPorts {
        val op = plan.getString("operation")
        val ext = plan.getString("extension")
        val jpeg = plan.getString("kind") == "JPEG"
        val mime = if (jpeg) "image/jpeg" else "video/mp4"
        val collection = if (jpeg) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val original = File(root, "$op-fixture.$ext")
        val backup = File(root, "$op-backup.$ext")
        val staged = File(root, "$op-dated.$ext")
        val originalName = "ML_QA_${op}_fixture.$ext"
        val newName = "ML_QA_${op}_restored.$ext"
        val prefs = context.getSharedPreferences("recovery-sandbox-$op", Context.MODE_PRIVATE)
        val dao = main.downloadDao()
        fun saved(key: String): Uri? = plan.optString(key).takeIf { it.isNotEmpty() }?.let(Uri::parse)
        fun persist(key: String, uri: Uri) { plan.put(key, uri.toString()); write("plan.json", plan) }
        fun ensureSpace(bytes: Long) {
            check(bytes in 1..(100L * 1024 * 1024)) { "Размер вне лимита искусственной пробы" }
            check(RecoverySafety.enoughSpace(bytes, StatFs(root.path).availableBytes,
                StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes)) {
                "Недостаточно свободного места; оригиналы сохранены"
            }
        }
        fun find(name: String): Uri? = (if (Build.VERSION.SDK_INT >= 30) {
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "relative_path = ? AND _display_name = ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(PATH, name))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }, null)
        } else if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            val includingPending = MediaStore.setIncludePending(collection)
            resolver.query(includingPending, arrayOf(MediaStore.MediaColumns._ID),
                "relative_path = ? AND _display_name = ?", arrayOf(PATH, name), null)
        } else error("Нужен Android 10 или новее"))?.use { c ->
            check(c.count <= 1) { "Неоднозначное имя: несколько MediaStore-записей" }
            if (c.moveToFirst()) android.content.ContentUris.withAppendedId(collection, c.getLong(0)) else null
        }
        fun identity(uri: Uri, name: String, expectedHash: String? = null) {
            check(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY && uri.toString().startsWith(collection.toString() + "/"))
            val columns = mutableListOf("owner_package_name", "relative_path", "_display_name", "mime_type", "is_pending")
            if (Build.VERSION.SDK_INT >= 30) columns += "is_trashed"
            resolver.query(uri, columns.toTypedArray(), null, null, null)?.use { c ->
                check(c.count == 1 && c.moveToFirst())
                if (Build.VERSION.SDK_INT >= 30) check(c.getInt(5) == 0) { "QA-файл оказался в корзине" }
                check(RecoverySafety.identity(c.getString(0), context.packageName, c.getString(1), c.getString(2), name, c.getString(3), mime)) {
                    "Изменилась идентичность QA-записи"
                }
            } ?: error("MediaStore недоступен")
            if (expectedHash != null) check(hash(uri) == expectedHash) { "Контрольная сумма не совпала" }
        }
        fun ensureRow(key: String, name: String, bytes: File): Uri {
            saved(key)?.let { identity(it, name); return it }
            val found = find(name)
            if (found != null) {
                // Recover only an owned pending row at the exact journaled UUID name.
                identity(found, name)
                resolver.query(found, arrayOf("is_pending"), null, null, null)!!.use { c -> check(c.moveToFirst() && c.getInt(0) == 1) }
                persist(key, found)
                return found
            }
            ensureSpace(bytes.length())
            val uri = resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.RELATIVE_PATH, PATH)
                put(MediaStore.MediaColumns.MIME_TYPE, mime); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("Не удалось создать QA-запись")
            persist(key, uri) // before opening a write descriptor
            identity(uri, name)
            return uri
        }
        fun writePending(uri: Uri, name: String, bytes: File) {
            identity(uri, name)
            resolver.query(uri, arrayOf("is_pending"), null, null, null)!!.use { c ->
                check(c.moveToFirst())
                if (c.getInt(0) == 0) { check(hash(uri) == hash(bytes)); return }
            }
            ensureSpace(bytes.length())
            resolver.openFileDescriptor(uri, "rwt")!!.use { fd ->
                FileOutputStream(fd.fileDescriptor).use { out -> bytes.inputStream().use { it.copyTo(out, 65536) }; out.flush(); out.fd.sync() }
            }
            check(hash(uri) == hash(bytes))
        }
        fun publish(uri: Uri, name: String, historical: Boolean) {
            identity(uri, name)
            if (historical) {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use { c ->
                    check(c.moveToFirst()); val f = File(c.getString(0))
                    check(f.canonicalFile.parentFile == File(android.os.Environment.getExternalStorageDirectory(), PATH).canonicalFile)
                    check(f.setLastModified(HISTORICAL))
                }
            }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        }
        override fun verifyIdentityAndBackup() {
            check(plan.getBoolean("synthetic") && plan.getInt("version") == 1)
            check(ext == if (jpeg) "jpg" else "mp4")
            check(hash(original) == plan.getString("originalSha") && original.length() == plan.getLong("bytes"))
            if (!plan.optBoolean("seeded")) ensureSpace(plan.getLong("bytes"))
            if (!backup.exists()) snapshot(original, backup)
            check(hash(backup) == plan.getString("originalSha"))
            val old = ensureRow("oldUri", originalName, original)
            if (!plan.optBoolean("seeded")) { writePending(old, originalName, original); publish(old, originalName, false) }
            identity(old, originalName, plan.getString("originalSha"))
            runBlocking { main.withTransaction {
                if (dao.byId(op) == null) {
                    // Only a generated fixture can seed a new, isolated history DB.
                    check(!plan.optBoolean("seeded")) { "История QA потеряна" }
                    dao.upsert(DownloadEntity(op, "qa-synthetic", originalName, if (jpeg) MediaType.PHOTO else MediaType.VIDEO,
                        DownloadState.COMPLETED, old.toString(), original.length(), HISTORICAL,
                        sourceUrl = "https://example.invalid/fixture", sourcePageUrl = "https://example.invalid/source",
                        bulkMediaKey = "qa-bulk", groupId = "qa-group", derivedFromId = "qa-parent"))
                    main.catalogDao().insertFolder(CatalogFolderEntity("qa-album", "QA", "qa", HISTORICAL))
                    main.catalogDao().saveAssignment(CatalogAssignmentEntity(op, "qa-album", true))
                }
            } }
            val db = vk.writableDatabase
            if (!plan.optBoolean("seeded")) {
                db.execSQL("INSERT OR IGNORE INTO jobs(id,link,start_date,end_date,zone_id,phase,created_at) VALUES(?,?,?,?,?,?,?)",
                    arrayOf(op, "https://example.invalid/community", "2026-10-01", "2026-10-01", "UTC", "FINISHED", HISTORICAL))
                db.execSQL("INSERT OR IGNORE INTO hashes(sha256,saved_uri,photo_key) VALUES(?,?,?)", arrayOf(plan.getString("originalSha"), old.toString(), op))
                db.execSQL("INSERT OR IGNORE INTO images(job_id,photo_key,post_key,post_date,url,width,height,saved_uri,sha256) VALUES(?,?,?,?,?,?,?,?,?)",
                    arrayOf(op, op, "qa-post", HISTORICAL / 1000, "https://example.invalid/fixture", 320, 240, old.toString(), plan.getString("originalSha")))
                val entry = JSONObject().put("key", op).put("uri", old.toString()).put("aliases", JSONArray(listOf(op, old.toString())))
                    .put("favorite", true).put("folder", "qa-album").put("name", originalName).put("type", if (jpeg) "PHOTO" else "VIDEO")
                    .put("date", HISTORICAL).put("size", original.length()).put("duration", JSONObject.NULL)
                    .put("album", "qa-group").put("artist", JSONObject.NULL).put("title", JSONObject.NULL)
                val catalog = JSONObject().put("version", 1).put("entries", JSONArray().put(entry))
                val playlist = JSONArray().put(JSONObject().put("id", "qa-playlist").put("name", "QA")
                    .put("trackIds", JSONArray(listOf(op))).put("trackReferences", JSONObject().put(op, JSONArray(listOf(op, old.toString())))))
                check(prefs.edit().putString("catalog", catalog.toString()).putString("playlist", playlist.toString()).commit())
                main.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use {
                    check(it.moveToFirst() && it.getInt(0) == 0) { "Не завершён WAL checkpoint истории" }
                }
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use {
                    check(it.moveToFirst() && it.getInt(0) == 0) { "Не завершён WAL checkpoint VK" }
                }
                // Immutable snapshot of all sandbox stores before any switch.
                snapshot(File(root, "$op-history.db"), File(root, "$op-history.backup"))
                snapshot(isolatedVkFile(), File(root, "$op-vk.backup"))
                verifyDatabaseBackup(File(root, "$op-history.backup"), "downloads")
                verifyDatabaseBackup(File(root, "$op-vk.backup"), "images")
                write("$op-links.backup.json", JSONObject().put("catalog", prefs.getString("catalog", null)).put("playlist", prefs.getString("playlist", null)))
                plan.put("backupHistorySha", hash(File(root, "$op-history.backup")))
                plan.put("backupVkSha", hash(File(root, "$op-vk.backup")))
                plan.put("backupLinksSha", hash(File(root, "$op-links.backup.json")))
                plan.put("seeded", true); write("plan.json", plan)
            }
            check(File(root, "$op-history.backup").isFile && File(root, "$op-vk.backup").isFile)
            check(hash(File(root, "$op-history.backup")) == plan.getString("backupHistorySha"))
            check(hash(File(root, "$op-vk.backup")) == plan.getString("backupVkSha"))
            check(hash(File(root, "$op-links.backup.json")) == plan.getString("backupLinksSha"))
            if (RecoveryStage.valueOf(read("journal.json").getString("stage")) >= RecoveryStage.COPY_PENDING) {
                identity(saved("newUri") ?: error("Потерян URI копии"), newName,
                    if (read("journal.json").getBoolean("rollingBack")) null else plan.getString("newSha"))
            }
            verifyImmutableLinks()
        }
        fun isolatedVkFile() = File(root, "$op-vk-community-images.db")
        fun verifyImmutableLinks() = runBlocking {
            val row = dao.byId(op) ?: error("Пропала история")
            check(row.id == op && row.providerId == "qa-synthetic" && row.createdAtEpochMillis == HISTORICAL &&
                row.sourceUrl == "https://example.invalid/fixture" && row.sourcePageUrl == "https://example.invalid/source" &&
                row.groupId == "qa-group" && row.bulkMediaKey == "qa-bulk" && row.derivedFromId == "qa-parent" &&
                !row.isPrivate && !row.hidden && row.state == DownloadState.COMPLETED)
            check(main.catalogDao().assignmentById(op) == CatalogAssignmentEntity(op, "qa-album", true))
            check(dao.all().size == 1)
            val old = saved("oldUri")!!.toString()
            val new = saved("newUri")?.toString()
            check(row.savedUri == old || (new != null && row.savedUri == new))
            val isNew = row.savedUri != old
            val expected = DownloadEntity(op, "qa-synthetic", if (isNew) newName else originalName,
                if (jpeg) MediaType.PHOTO else MediaType.VIDEO, DownloadState.COMPLETED, row.savedUri,
                if (isNew) plan.getLong("newBytes") else original.length(), HISTORICAL,
                sourceUrl = "https://example.invalid/fixture", sourcePageUrl = "https://example.invalid/source",
                bulkMediaKey = "qa-bulk", groupId = "qa-group", derivedFromId = "qa-parent")
            check(row == expected) { "Изменился полный снимок строки истории" }
        }
        override fun copyPending() {
            ensureSpace(original.length())
            if (plan.has("newSha")) {
                check(staged.isFile && hash(staged) == plan.getString("newSha")) { "Повреждён проверенный staging" }
                val new = ensureRow("newUri", newName, staged)
                writePending(new, newName, staged)
                return
            }
            // Rebuilding a partial private staging file is safe; its URI is never exposed to readers.
            if (jpeg) {
                original.copyTo(staged, overwrite = true)
                val exif = ExifInterface(staged.path)
                val date = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(HISTORICAL))
                listOf(ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED).forEach { exif.setAttribute(it, date) }
                exif.setAttribute("OffsetTimeOriginal", "+00:00"); exif.saveAttributes()
                val baseline = ExifInterface(original.path)
                val excluded = setOf("DateTime", "DateTimeOriginal", "DateTimeDigitized", "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized")
                ExifInterface::class.java.fields.filter { it.name.startsWith("TAG_") && it.type == String::class.java }.forEach { field ->
                    val tag = field.get(null) as String
                    if (tag !in excluded) check(baseline.getAttribute(tag) == exif.getAttribute(tag)) { "Изменился EXIF вне даты" }
                }
            } else {
                ffmpeg(arrayOf("-y", "-i", original.path, "-map", "0", "-map_metadata", "0", "-c", "copy",
                    "-metadata", "creation_time=${Instant.ofEpochMilli(HISTORICAL)}", staged.path))
            }
            sync(staged)
            val digest = hash(staged)
            if (plan.has("newSha")) check(plan.getString("newSha") == digest) else {
                plan.put("newSha", digest).put("newBytes", staged.length()); write("plan.json", plan)
            }
            val new = ensureRow("newUri", newName, staged)
            writePending(new, newName, staged)
        }
        override fun verifyMetadataAndContent() {
            verifyOriginalRetained()
            val new = saved("newUri") ?: error("Нет новой QA-записи")
            identity(new, newName, plan.getString("newSha"))
            if (jpeg) check(pixelHash(original) == pixelHash(staged)) { "Изменились пиксели JPEG" }
            else {
                check(streamHash(original) == streamHash(staged)) { "Изменились видео/аудиопотоки" }
                // Decode both video and audio; error flags force nonzero on malformed packets.
                ffmpeg(arrayOf("-v", "error", "-xerror", "-i", staged.path, "-map", "0:v", "-map", "0:a", "-f", "null", "-"))
            }
            publish(new, newName, true)
            var matched = false
            repeat(10) {
                resolver.query(new, arrayOf("datetaken"), null, null, null)?.use { c ->
                    if (c.moveToFirst() && kotlin.math.abs(c.getLong(0) - HISTORICAL) <= 1000L) matched = true
                }
                if (!matched) Thread.sleep(300)
            }
            check(matched) { "Android ещё не подтвердил историческую дату; продолжение остановлено" }
        }
        override fun switchMain(forward: Boolean) = runBlocking {
            verifyImmutableLinks()
            val old = saved("oldUri")!!.toString()
            val new = saved("newUri")?.toString()
            val target = if (forward) new ?: error("Нет новой копии") else old
            val current = dao.byId(op)!!.savedUri
            check(current == old || (new != null && current == new)) { "CAS: посторонний URI" }
            if (forward) identity(Uri.parse(target), newName, plan.getString("newSha"))
            if (current != target) {
                val row = dao.byId(op)!!
                check(dao.rebindSavedFileWhenCurrent(op, current!!, row.originalName, row.sizeBytes!!,
                    target, if (forward) newName else originalName, if (forward) plan.getLong("newBytes") else original.length()) == 1)
            }
            verifyImmutableLinks()
        }
        override fun switchVk(forward: Boolean) {
            val old = saved("oldUri")!!.toString()
            val new = saved("newUri")?.toString()
            val target = if (forward) new ?: error("Нет новой копии") else old
            val sha = if (forward) plan.getString("newSha") else plan.getString("originalSha")
            val db = vk.writableDatabase
            db.beginTransaction()
            try {
                for (table in listOf("images", "hashes")) {
                    db.rawQuery("SELECT saved_uri FROM $table WHERE photo_key=?", arrayOf(op)).use { c ->
                        check(c.count == 1 && c.moveToFirst()); check(c.getString(0) == old || c.getString(0) == new)
                    }
                    check(db.update(table, ContentValues().apply { put("saved_uri", target); put("sha256", sha) }, "photo_key=?", arrayOf(op)) == 1)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        fun switchPreference(key: String, forward: Boolean) {
            val old = saved("oldUri")!!.toString(); val new = saved("newUri")?.toString()
            val target = if (forward) new ?: error("Нет новой копии") else old
            val raw = prefs.getString(key, null) ?: error("Нет индекса $key")
            val from = if (forward) old else new ?: old
            val updated = if (key == "catalog") RecoveryLinkDocuments.catalog(raw, op, from, target)
                else RecoveryLinkDocuments.playlists(raw, op, from, target)
            check(prefs.edit().putString(key, updated).commit())
        }
        override fun switchCatalog(forward: Boolean) = switchPreference("catalog", forward)
        override fun switchPlaylists(forward: Boolean) = switchPreference("playlist", forward)
        override fun verifyReopened() {
            verifyImmutableLinks()
            val uri = saved("newUri")!!
            identity(uri, newName, plan.getString("newSha"))
            if (jpeg) resolver.openInputStream(uri)!!.use { input ->
                val b = BitmapFactory.decodeStream(input) ?: error("Новая копия не открывается"); b.recycle()
            } else {
                val retriever = MediaMetadataRetriever()
                try { retriever.setDataSource(context, uri); val frame = retriever.getFrameAtTime(0) ?: error("Нет кадра"); frame.recycle() }
                finally { retriever.release() }
            }
            verifyReferences(uri.toString(), plan.getString("newSha"))
            verifyOriginalRetained()
        }
        fun verifyReferences(uri: String, sha: String) = runBlocking {
            check(dao.byId(op)!!.savedUri == uri)
            val catalog = JSONObject(prefs.getString("catalog", null)!!).getJSONArray("entries").getJSONObject(0)
            check(catalog.getString("uri") == uri && catalog.getString("key") == op && catalog.getBoolean("favorite") && catalog.getString("folder") == "qa-album")
            check(catalog.getJSONArray("aliases").getString(1) == uri)
            val playlist = JSONArray(prefs.getString("playlist", null)!!).getJSONObject(0)
            check(playlist.getJSONArray("trackIds").getString(0) == op && playlist.getJSONObject("trackReferences").getJSONArray(op).getString(1) == uri)
            for (table in listOf("images", "hashes")) vk.readableDatabase.rawQuery(
                "SELECT saved_uri,sha256 FROM $table WHERE photo_key=?", arrayOf(op)).use { c ->
                check(c.count == 1 && c.moveToFirst() && c.getString(0) == uri && c.getString(1) == sha)
            }
        }
        override fun verifyOriginalRetained() {
            check(hash(original) == plan.getString("originalSha") && hash(backup) == plan.getString("originalSha"))
            identity(saved("oldUri")!!, originalName, plan.getString("originalSha"))
        }
        override fun verifyRollback() {
            verifyOriginalRetained(); verifyImmutableLinks()
            verifyReferences(saved("oldUri")!!.toString(), plan.getString("originalSha"))
        }
    }
    private fun snapshot(from: File, to: File) {
        if (!to.exists()) {
            val atomic = AtomicFile(to)
            val stream = atomic.startWrite()
            try { from.inputStream().use { it.copyTo(stream) }; atomic.finishWrite(stream) }
            catch (error: Throwable) { atomic.failWrite(stream); throw error }
        }
        check(hash(from) == hash(to)) { "Резерв базы не совпал" }
    }
    private fun verifyDatabaseBackup(file: File, table: String) {
        check(table in setOf("downloads", "images"))
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                check(c.moveToFirst() && c.getString(0) == "ok" && !c.moveToNext()) { "Повреждён резерв базы" }
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) { "Нарушены связи в резерве базы" } }
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> check(c.moveToFirst() && c.getInt(0) == 1) }
        }
    }
    private fun sync(file: File) { RandomAccessFile(file, "rw").use { it.fd.sync() } }
    private fun hash(file: File) = file.inputStream().use(::hashStream)
    private fun hash(uri: Uri) = resolver.openInputStream(uri)!!.use(::hashStream)
    private fun hashStream(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun pixelHash(file: File): String {
        val bitmap = BitmapFactory.decodeFile(file.path) ?: error("JPEG не декодируется")
        try {
            check(bitmap.width == 320 && bitmap.height == 240) { "Неожиданные размеры искусственного JPEG" }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val buffer = java.nio.ByteBuffer.allocate(pixels.size * 4)
            pixels.forEach(buffer::putInt)
            return MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
        }
        finally { bitmap.recycle() }
    }
    private fun ffmpeg(args: Array<String>): String {
        val session = FFmpegKit.executeWithArguments(args)
        check(ReturnCode.isSuccess(session.returnCode)) { "FFmpeg: проверка искусственного медиа не пройдена" }
        return session.output ?: error("Нет результата FFmpeg")
    }
    private fun streamHash(file: File): List<String> = ffmpeg(arrayOf("-v", "error", "-i", file.path,
        "-map", "0", "-c", "copy", "-f", "streamhash", "-hash", "sha256", "-")).lineSequence()
        .filter { it.matches(Regex("[0-9]+,[avsd],SHA256=[a-fA-F0-9]{64}")) }.toList().also { check(it.size >= 2) }
}
