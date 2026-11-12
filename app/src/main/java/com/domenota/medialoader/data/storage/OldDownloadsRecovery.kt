package com.domenota.medialoader.data.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaExtractor
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
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import com.domenota.medialoader.data.library.ExternalMediaCatalogRepository
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

internal object OldDownloadsPolicy {
    const val SOURCE = "Download/MediaLoader/"
    const val EXCLUDED = "IMG_SEGMENT_20261105_185547.png"
    const val ADDICT = "ОТЕЛЬ ХАЗБИН ADDICT ft.@_silentium КАВЕР НА РУССКОМ_360p.mp4"
    const val COTTON = "COTTON CANDY - КАВЕР НА РУССКОМ COTTON CANDY - RUS COVER.mp3"
    fun target(type: MediaType) = if (type == MediaType.AUDIO) "Music/MediaLoader/" else "Pictures/MediaLoader/"
    fun confirmedProvider(name: String, type: MediaType, confirmed: Boolean): String? {
        if (!confirmed || name.equals(EXCLUDED, true)) return null
        return when {
            name == ADDICT && type == MediaType.VIDEO -> "youtube"
            name == COTTON && type == MediaType.AUDIO -> "youtube"
            type == MediaType.AUDIO -> "recovered"
            else -> "instagram"
        }
    }
    fun validSelection(trial: Boolean, uris: List<String>) = uris.isNotEmpty() && uris.distinct().size == uris.size && (!trial || uris.size == 1)
    fun metadataProblems(name: String, typeMatches: Boolean, bytes: Long, added: Long, modified: Long,
        pending: Long?, trashed: Long?, checkTrash: Boolean): List<String> = buildList {
        if (!safeName(name)) add("Недопустимое имя")
        if (!typeMatches) add("MIME не соответствует коллекции")
        if (bytes <= 0) add("Размер не подтверждён: $bytes")
        if (added <= 0) add("DATE_ADDED не подтверждён: $added")
        if (modified <= 0) add("DATE_MODIFIED не подтверждён: $modified")
        if (pending != 0L) add("IS_PENDING=${pending ?: "null"}")
        if (checkTrash && trashed != 0L) add("IS_TRASHED=${trashed ?: "null"}")
    }
    fun safeName(name: String) = name.isNotBlank() && name !in setOf(".", "..") &&
        name.none { it == '/' || it == '\\' || it.isISOControl() }
}

internal data class OldDownloadCandidate(val uri: String, val name: String, val type: MediaType,
    val receivedMillis: Long, val bytes: Long, val provider: String?, val missingHistory: Boolean,
    val problem: String?, val snapshot: String, val historySnapshot: String?, val needsWriteGrant: Boolean = false)
internal data class OldDownloadInventory(val candidates: List<OldDownloadCandidate>, val skipped: List<String>,
    val pending: Boolean, val trialReady: Boolean, val trialConfirmed: Boolean, val trialName: String?, val message: String,
    val diagnostics: List<String> = emptyList(), val pendingWriteUris: List<String> = emptyList())

/** A different operation from historical-date replacement: no copy is published, no bytes are deleted,
 * no timestamps are assigned. A stable primary-volume row may move with actual Android write access. */
internal class OldDownloadsRecovery(ctx: Context, private val db: HistoryDatabase) {
    private val context = ctx.applicationContext
    private val resolver get() = context.contentResolver
    private val dao get() = db.downloadDao()
    private val links = HistoricalReferenceLinks(context, db)
    private val root = File(context.noBackupFilesDir, "old-downloads-recovery-v1")
    private val journal = "operation.json"
    private val receipt = "trial.json"

    companion object {
        fun pending(context: Context): Boolean {
            val f = File(context.noBackupFilesDir, "old-downloads-recovery-v1/operation.json")
            if (File(f.path + ".bak").exists()) return true
            return f.exists() && runCatching { !JSONObject(f.readText()).getBoolean("done") }.getOrDefault(true)
        }
    }
    private fun hasWriteGrant(uri: Uri): Boolean = context.checkUriPermission(uri,
        android.os.Process.myPid(), android.os.Process.myUid(), android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun needsGrant(s: JSONObject): Boolean = s.optString("owner", "") != context.packageName &&
        !hasWriteGrant(Uri.parse(s.getString("uri")))
    private fun requireWriteAccess(s: JSONObject) {
        check(!needsGrant(s)) { "Нет системного разрешения записи: ${s.getString("name")}" }
    }
    private fun supported() { check(Build.VERSION.SDK_INT >= 29) { "MediaStore: Android 10+" } }
    private fun read(name: String): JSONObject? {
        val f = AtomicFile(File(root, name))
        if (!f.baseFile.exists() && !File(f.baseFile.path + ".bak").exists()) return null
        return JSONObject(f.openRead().bufferedReader().use { it.readText() }).also { check(it.getInt("version") == 1) }
    }
    private fun write(name: String, value: JSONObject) {
        check(root.isDirectory || root.mkdirs()) { "Журнал переноса недоступен" }
        val f = AtomicFile(File(root, name)); val out = f.startWrite()
        try { out.write(value.toString().toByteArray(Charsets.UTF_8)); f.finishWrite(out) }
        catch (e: Throwable) { f.failWrite(out); throw e }
    }
    private fun type(mime: String): MediaType? = when (MediaStoragePolicy.kind(mime)) {
        MediaStoragePolicy.Kind.IMAGE -> MediaType.PHOTO
        MediaStoragePolicy.Kind.VIDEO -> MediaType.VIDEO
        MediaStoragePolicy.Kind.AUDIO -> MediaType.AUDIO
        else -> null
    }
    private fun collection(type: MediaType): Uri = when(type) {
        MediaType.PHOTO -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        MediaType.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        MediaType.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
    private fun query(uri: Uri, cols: Array<String>, selection: String? = null, args: Array<String>? = null): Cursor? =
        if (Build.VERSION.SDK_INT >= 30) resolver.query(uri, cols, Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            if (args != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        }, null) else resolver.query(uri, cols, selection, args, null)
    private fun sha(input: java.io.InputStream): String = input.use {
        val digest = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024)
        while (true) { val count = it.read(buf); if (count < 0) break; digest.update(buf, 0, count) }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
    private fun hash(uri: Uri): String = sha(resolver.openInputStream(uri) ?: error("Файл недоступен для SHA-256"))
    private fun actualMime(uri: Uri, type: MediaType): String {
        if (type == MediaType.PHOTO) {
            val head = ByteArray(16); val size = resolver.openInputStream(uri)?.use { stream ->
                var offset = 0; while (offset < head.size) { val n = stream.read(head, offset, head.size - offset); if (n <= 0) break; offset += n }; offset
            } ?: 0
            fun at(start: Int, vararg bytes: Int) = size >= start + bytes.size && bytes.indices.all { (head[start + it].toInt() and 255) == bytes[it] }
            return when {
                at(0,255,216,255) -> "image/jpeg"
                at(0,137,80,78,71,13,10,26,10) -> "image/png"
                at(0,82,73,70,70) && at(8,87,69,66,80) -> "image/webp"
                at(0,71,73,70,56) -> "image/gif"
                else -> error("Формат изображения не подтверждён")
            }
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val prefix = if (type == MediaType.AUDIO) "audio/" else "video/"
            return (0 until extractor.trackCount).mapNotNull { extractor.getTrackFormat(it).getString("mime") }
                .firstOrNull { it.startsWith(prefix) } ?: error("Медиадорожка не подтверждена")
        } finally { extractor.release() }
    }
    private fun media(uri: Uri, expectedType: MediaType, report: ((String) -> Unit)? = null): JSONObject {
        val cols = mutableListOf("_id", "_data", "relative_path", "_display_name", "mime_type", "owner_package_name",
            "_size", "date_added", "date_modified", "is_pending")
        if (expectedType != MediaType.AUDIO) cols += "datetaken"
        if (Build.VERSION.SDK_INT >= 30) cols += "is_trashed"
        val value = query(uri, cols.toTypedArray())?.use { c ->
            check(c.moveToFirst()) { "Запись MediaStore отсутствует" }
            fun str(name: String): String? = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
            fun num(name: String): Long? = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
            report?.invoke("URI=$uri · id=${num("_id")} · путь=${str("relative_path")} · имя=${str("_display_name")} · MIME=${str("mime_type")} · owner=${str("owner_package_name")} · size=${num("_size")} · date_added=${num("date_added")} · date_modified=${num("date_modified")} · datetaken=${num("datetaken")} · pending=${num("is_pending")} · trashed=${num("is_trashed")} · raw=${str("_data")}")
            check(num("_id") == uri.lastPathSegment?.toLongOrNull()) { "Изменился MediaStore ID" }
            val path = str("relative_path") ?: error("Папка MediaStore недоступна")
            check(path == OldDownloadsPolicy.SOURCE || path == OldDownloadsPolicy.target(expectedType)) { "Путь вне области переноса" }
            val name = str("_display_name") ?: error("Имя недоступно")
            val mime = str("mime_type") ?: error("MIME недоступен")
            val owner = str("owner_package_name")
            val bytes = num("_size") ?: 0L
            val added = num("date_added") ?: 0L
            val modified = num("date_modified") ?: 0L
            val pending = num("is_pending")
            val trashed = num("is_trashed")
            val problems = OldDownloadsPolicy.metadataProblems(name, type(mime) == expectedType, bytes, added, modified, pending, trashed, Build.VERSION.SDK_INT >= 30)
            check(problems.isEmpty()) { problems.joinToString("; ") }
            check(owner == context.packageName || Build.VERSION.SDK_INT >= 30) { "Владелец MediaStore: ${owner ?: "не указан"}; системное разрешение записи требует Android 11+" }
            check(MediaStoragePolicy.legacyMime(expectedType, name, mime) != null) { "Неподдерживаемый формат" }
            val physical = str("_data") ?: error("Физический путь недоступен")
            val expected = File(Environment.getExternalStorageDirectory(), path + name).canonicalFile
            check(File(physical).canonicalFile == expected && expected.isFile) { "Физический путь не совпал" }
            val mtime = expected.lastModified()
            report?.invoke("Физический путь=${expected.path} · mtime=$mtime · фактический размер=${expected.length()}")
            check(mtime > 0) { "Физическая дата изменения недоступна: $mtime" }
            check(expected.length() == bytes) { "Размер MediaStore и файла не совпал: $bytes / ${expected.length()}" }
            JSONObject().put("uri", uri.toString()).put("type", expectedType.name).put("path", path)
                .put("name", name).put("mime", mime).put("owner", owner ?: JSONObject.NULL).put("bytes", bytes)
                .put("added", added).put("modified", modified).put("taken", num("datetaken") ?: JSONObject.NULL)
                .put("mtime", mtime).put("physical", expected.path)
        } ?: error("Запись MediaStore недоступна")
        return value.put("actualMime", actualMime(uri, expectedType)).put("sha", hash(uri)).also {
            report?.invoke("Формат=${it.getString("actualMime")} · физический mtime=${it.getLong("mtime")} · SHA-256=${it.getString("sha")} · разрешение записи=${!needsGrant(it)}")
        }
    }
    private fun keys(snapshot: JSONObject): Set<String> = PublicMediaIdentity.uriKeys(snapshot.getString("uri")) +
        PublicMediaIdentity.pathKeys(snapshot.getString("physical"))
    private fun rowJson(row: DownloadEntity): JSONObject = JSONObject().also { j ->
        DownloadEntity::class.java.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }.sortedBy { it.name }.forEach {
            it.isAccessible = true; val v = it.get(row); j.put(it.name, if (v is Enum<*>) v.name else v ?: JSONObject.NULL)
        }
    }
    private fun matching(snapshot: JSONObject, rows: List<DownloadEntity>): List<DownloadEntity> {
        val identities = keys(snapshot)
        return rows.filter { row -> listOfNotNull(row.savedUri, row.privateSourceUri).any { uri ->
            PublicMediaIdentity.uriKeys(uri).any(identities::contains)
        } }
    }
    private fun sameSnapshot(before: JSONObject, current: JSONObject, target: String): Boolean {
        val expected = JSONObject(before.toString()).put("path", target)
            .put("physical", File(Environment.getExternalStorageDirectory(), target + before.getString("name")).canonicalPath)
        return expected.toString() == current.toString()
    }
    private fun targetConflict(s: JSONObject): String? {
        val target = OldDownloadsPolicy.target(MediaType.valueOf(s.getString("type")))
        val name = s.getString("name")
        val physical = File(Environment.getExternalStorageDirectory(), target + name)
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val names = query(uri, arrayOf("_id", "_display_name"), "relative_path=?", arrayOf(target))?.use { c ->
            buildList<Long> { while (c.moveToNext()) if (c.getString(1).equals(name, true)) add(c.getLong(0)) }
        } ?: error("Проверка папки назначения недоступна")
        if (physical.exists() || names.isNotEmpty()) {
            val digest = runCatching {
                if (physical.isFile) sha(physical.inputStream())
                else if (names.size == 1) hash(ContentUris.withAppendedId(collection(MediaType.valueOf(s.getString("type"))), names.single()))
                else null
            }.getOrNull()
            return if (digest == s.getString("sha")) "Совпадающая копия в назначении; без удаления или дубля"
                else "Конфликт имени в папке назначения"
        }
        return null
    }
    private suspend fun inventory(diagnostics: MutableList<String> = mutableListOf()): Pair<List<OldDownloadCandidate>, List<String>> {
        supported()
        val rows = dao.all(); val candidates = mutableListOf<OldDownloadCandidate>(); val skipped = mutableListOf<String>()
        val foundNames = mutableSetOf<String>()
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val indexed = query(files, arrayOf("_id", "_display_name", "mime_type"), "relative_path=?", arrayOf(OldDownloadsPolicy.SOURCE))?.use { c ->
            buildList<Triple<Long, String, String>> { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())) }
        } ?: error("Инвентаризация MediaStore недоступна")
        for ((id, name, mime) in indexed) {
            coroutineContext.ensureActive(); foundNames += name
            if (name.equals(OldDownloadsPolicy.EXCLUDED, true)) { skipped += "$name · исключён"; continue }
            val t: MediaType = type(mime) ?: run {
                skipped += "$name · неподдерживаемый формат"
                return@run null
            } ?: continue
            val uri = ContentUris.withAppendedId(collection(t), id)
            try {
                val s = media(uri, t) { diagnostics += "$name · $it" }
                check(s.getString("path") == OldDownloadsPolicy.SOURCE && s.getString("name") == name) { "Запись вне исходной папки" }
                val matches = matching(s, rows)
                val row = matches.singleOrNull()
                val problem = when {
                    matches.size > 1 -> "Неоднозначные ссылки истории"
                    row != null && (row.hidden || row.isPrivate || row.state != DownloadState.COMPLETED ||
                        row.mediaType != t || row.originalName != name || row.sizeBytes != s.getLong("bytes")) -> "История не совпадает или запись скрыта"
                    row != null && (t != MediaType.AUDIO || name == OldDownloadsPolicy.COTTON) &&
                        row.providerId != OldDownloadsPolicy.confirmedProvider(name, t, true) -> "Источник истории отличается от подтверждённого"
                    row == null && rows.any { it.originalName == name && it.sizeBytes == s.getLong("bytes") } -> "Возможная старая запись без доказанного URI"
                    else -> targetConflict(s)
                }
                candidates += OldDownloadCandidate(uri.toString(), name, t, s.getLong("added") * 1000L,
                    s.getLong("bytes"), row?.providerId, row == null, problem, s.toString(), row?.let { rowJson(it).toString() }, needsGrant(s))
                diagnostics += "$name · история=${row?.id ?: "отсутствует"} · ${problem ?: "метаданные подтверждены"}"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { val reason = "$name · ${e.message ?: e.javaClass.simpleName}"; skipped += reason; diagnostics += reason }
        }
        val raw = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MediaLoader")
        val local = raw.listFiles()
        if (local == null) skipped += "Файловый список вне MediaStore недоступен"
        else local.filter { it.isFile && it.name !in foundNames && !it.name.equals(OldDownloadsPolicy.EXCLUDED, true) }
            .forEach { skipped += "${it.name} · нет подтверждённой записи MediaStore" }
        return candidates.sortedBy { it.name.lowercase() } to skipped
    }
    private suspend fun verifyTrial(r: JSONObject): Boolean = try {
        val s = r.getJSONObject("snapshot")
        val t = MediaType.valueOf(s.getString("type"))
        val current = media(Uri.parse(s.getString("uri")), t)
        sameSnapshot(s, current, OldDownloadsPolicy.target(t)) &&
            dao.byId(r.getString("historyId"))?.let { row ->
                row.savedUri == s.getString("uri") && !row.hidden && !row.isPrivate && row.state == DownloadState.COMPLETED &&
                    row.originalName == s.getString("name") && row.sizeBytes == s.getLong("bytes") &&
                    row.createdAtEpochMillis == JSONObject(r.getString("historySnapshot")).getLong("createdAtEpochMillis")
            } == true
    } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    suspend fun inspect(): OldDownloadInventory = withContext(Dispatchers.IO) {
        val batch = read(journal); val pending = batch != null && !batch.getBoolean("done")
        val r = read(receipt); val ready = r != null && verifyTrial(r)
        val diagnostics = mutableListOf<String>()
        val (items, skipped) = if (pending) emptyList<OldDownloadCandidate>() to emptyList<String>() else inventory(diagnostics)
        val grants = if (pending) {
            val entries = batch!!.getJSONArray("items")
            (batch.getInt("cursor") until entries.length()).mapNotNull { index ->
                val op = entries.getJSONObject(index)
                val s = op.getJSONObject("snapshot")
                s.getString("uri").takeIf { op.getString("stage") in setOf("PREPARED", "MOVE_INTENT") && needsGrant(s) }
            }
        } else emptyList()
        diagnostics.forEach { com.domenota.medialoader.core.logging.AppLog.i("OldDownloads", it) }
        OldDownloadInventory(items, skipped, pending, ready, ready && r!!.optBoolean("confirmed"),
            r?.optJSONObject("snapshot")?.optString("name"),
            if (pending) batch!!.optString("message") else "Готово к проверке: ${items.count { it.problem == null }} · ограничений: ${skipped.size + items.count { it.problem != null }}",
            diagnostics, grants)
    }
    suspend fun confirmTrial(): Unit = withContext(Dispatchers.IO) {
        check(!pending(context)) { "Незавершённый перенос" }
        val r = read(receipt) ?: error("Одиночная проверка отсутствует")
        check(verifyTrial(r)) { "Снимок одиночной проверки изменился" }
        write(receipt, r.put("confirmed", true))
    }
    suspend fun start(selected: List<OldDownloadCandidate>, trial: Boolean, confirmedMissing: Boolean,
        progress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        supported(); links.ensureIdle(); check(!pending(context)) { "Незавершённый перенос" }
        check(OldDownloadsPolicy.validSelection(trial, selected.map { it.uri })) { "Недопустимый набор файлов" }
        if (!trial) { val r = read(receipt) ?: error("Одиночная проверка отсутствует"); check(r.optBoolean("confirmed") && verifyTrial(r)) { "Массовый режим не подтверждён" } }
        val (fresh, _) = inventory()
        val selectedFresh = selected.map { old ->
            val now = fresh.singleOrNull { it.uri == old.uri } ?: error("Файл отсутствует: ${old.name}")
            val oldSnapshot = JSONObject(old.snapshot)
            val freshSnapshot = JSONObject(now.snapshot)
            val comparable = if (oldSnapshot.isNull("owner")) freshSnapshot.put("owner", JSONObject.NULL).toString() else now.snapshot
            check(now.copy(needsWriteGrant = old.needsWriteGrant, snapshot = comparable) == old && now.problem == null) { "Снимок файла изменился: ${old.name}" }
            check(!now.missingHistory || confirmedMissing) { "Источник и восстановление истории не подтверждены" }
            requireWriteAccess(JSONObject(now.snapshot))
            now
        }
        val entries = JSONArray()
        selectedFresh.forEach { c ->
            val snapshot = JSONObject(c.snapshot)
            val provider = c.provider ?: OldDownloadsPolicy.confirmedProvider(c.name, c.type, confirmedMissing) ?: error("Источник не подтверждён")
            val previous = c.historySnapshot?.let(::JSONObject)
            val id = previous?.getString("id") ?: "recovered-" + UUID.nameUUIDFromBytes((c.uri + ":" + snapshot.getString("sha") + ":" + snapshot.getLong("added")).toByteArray())
            entries.put(JSONObject().put("snapshot", snapshot).put("historyId", id).put("provider", provider)
                .put("oldRow", previous ?: JSONObject.NULL).put("stage", "PREPARED"))
        }
        if (trial) { File(root, receipt).delete(); File(root, "$receipt.bak").delete() }
        val old = read(journal); if (old != null) write("finished-${old.getString("batch")}.json", old)
        val batch = JSONObject().put("version", 1).put("batch", UUID.randomUUID().toString())
            .put("trial", trial).put("items", entries).put("cursor", 0).put("done", false).put("message", "Подготовлено")
        write(journal, batch); advance(batch, progress)
    }
    suspend fun resume(progress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        supported(); links.ensureIdle(); val batch = read(journal) ?: error("Журнал отсутствует")
        check(!batch.getBoolean("done")); advance(batch, progress)
    }
    private suspend fun verifyReferencesBefore(op: JSONObject) {
        val s = op.getJSONObject("snapshot"); val old = op.optJSONObject("oldRow"); val row = dao.byId(op.getString("historyId"))
        if (old != null) {
            check(row != null && rowJson(row).toString() == old.toString()) { "Снимок истории изменился" }
            check(matching(s, dao.all()).singleOrNull()?.id == row.id) { "Связи истории изменились" }
        } else {
            check(row == null && matching(s, dao.all()).isEmpty()) { "Появилась запись истории" }
            val name = s.getString("name"); val bytes = s.getLong("bytes")
            check(dao.all().none { it.originalName == name && it.sizeBytes == bytes }) { "Возможная старая запись без доказанного URI" }
        }
        val oldUri = old?.getString("savedUri")
        val actual = s.getString("uri")
        if (oldUri != null && oldUri != actual) {
            com.domenota.medialoader.data.playback.AudioPlaybackService.requireNoLegacyQueueUri(oldUri, actual)
            op.put("links", links.snapshot(requireNotNull(row), keys(s), actual))
        } else {
            // Existing typed URI and physical ID survive the move. A separate path-only
            // catalog reference would not; do not silently invalidate it.
            val catalog = ExternalMediaCatalogRepository(context).snapshotHistoricalMedia()
            if (catalog.isNotEmpty()) {
                val arr = JSONObject(catalog).getJSONArray("entries")
                val identities = keys(s)
                for (i in 0 until arr.length()) {
                    val entry = arr.getJSONObject(i); val saved = entry.getString("uri")
                    val aliases = entry.getJSONArray("aliases")
                    val matches = PublicMediaIdentity.uriKeys(saved).any(identities::contains) ||
                        (0 until aliases.length()).any { aliases.getString(it) in identities }
                    check(!matches || PublicMediaIdentity.uriKeys(saved).any { it.startsWith("media:") && it in identities }) {
                        "Каталог содержит ссылку, требующую отдельной перепривязки"
                    }
                }
            }
        }
    }
    private fun backupFile(batch: JSONObject, index: Int): File {
        val batchId = UUID.fromString(batch.getString("batch")).toString()
        return File(root, "$batchId-$index.original")
    }
    private fun backup(s: JSONObject, file: File) {
        if (!file.exists()) {
            check(StatFs(root.path).availableBytes > s.getLong("bytes") + 32L * 1024 * 1024) { "Недостаточно места для резерва" }
            val temp = File(file.path + ".partial")
            FileOutputStream(temp).use { out ->
                resolver.openInputStream(Uri.parse(s.getString("uri")))?.use { it.copyTo(out) } ?: error("Исходник недоступен")
                out.fd.sync()
            }
            check(temp.length() == s.getLong("bytes") && sha(temp.inputStream()) == s.getString("sha")) { "Резерв не подтверждён" }
            check(temp.renameTo(file)) { "Резерв не сохранён" }
        }
        check(file.length() == s.getLong("bytes") && sha(file.inputStream()) == s.getString("sha")) { "Резерв изменился" }
    }
    private fun move(s: JSONObject, type: MediaType) {
        val uri = Uri.parse(s.getString("uri")); val target = OldDownloadsPolicy.target(type)
        val before = media(uri, type)
        if (sameSnapshot(s, before, target)) return // prior move committed before checkpoint
        check(sameSnapshot(s, before, OldDownloadsPolicy.SOURCE)) { "Исходные даты или содержимое изменились" }
        requireWriteAccess(before)
        check(targetConflict(s) == null) { "Папка назначения: конфликт имени" }
        try {
            check(resolver.update(uri, ContentValues().apply { put("relative_path", target) }, null, null) == 1) { "MediaStore отклонил перенос" }
            check(sameSnapshot(s, media(uri, type), target)) { "Даты или содержимое после переноса не совпали" }
        } catch (failure: Exception) {
            val restored = runCatching {
                val path = query(uri, arrayOf("relative_path"))?.use { if (it.moveToFirst()) it.getString(0) else null }
                if (path == target) resolver.update(uri, ContentValues().apply { put("relative_path", OldDownloadsPolicy.SOURCE) }, null, null)
                sameSnapshot(s, media(uri, type), OldDownloadsPolicy.SOURCE)
            }.getOrDefault(false)
            throw IllegalStateException(if (restored) "Перенос отклонён, исходный снимок сохранён: ${failure.message}"
                else "Исходные метаданные не подтверждены после отката. Резерв и журнал сохранены; серия остановлена", failure)
        }
    }
    private suspend fun bind(op: JSONObject): DownloadEntity {
        val s = op.getJSONObject("snapshot"); val old = op.optJSONObject("oldRow")
        val id = op.getString("historyId"); val uri = s.getString("uri"); val t = MediaType.valueOf(s.getString("type"))
        if (old == null) {
            val new = DownloadEntity(id, op.getString("provider"), s.getString("name"), t, DownloadState.COMPLETED,
                uri, s.getLong("bytes"), s.getLong("added") * 1000L, bytesDownloaded = s.getLong("bytes"))
            db.withTransaction {
                val current = dao.byId(id)
                if (current == null) { check(matching(s, dao.all()).isEmpty()); dao.insertRecovered(new) }
                else check(current == new) { "Восстановленная запись изменилась" }
            }
        } else {
            val oldUri = old.getString("savedUri")
            val desired = JSONObject(old.toString()).put("savedUri", uri)
            val desiredLinks = JSONObject(desired.toString())
            for (key in listOf("previewUrl", "sourceAudioArtworkUrl", "recognitionArtworkUrl"))
                if (!desiredLinks.isNull(key) && desiredLinks.getString(key) == oldUri) desiredLinks.put(key, uri)
            db.withTransaction {
                val current = dao.byId(id) ?: error("Запись истории исчезла")
                val actual = rowJson(current).toString()
                check(actual == old.toString() || actual == desired.toString() || actual == desiredLinks.toString()) { "Снимок истории изменился" }
                if (oldUri != uri && actual == old.toString()) {
                    check(dao.countSavedUriReferences(uri) == 0)
                    check(dao.moveSavedUriWhenCurrent(id, oldUri, uri, s.getString("name")) == 1)
                }
            }
            if (oldUri != uri) links.rebind(op.getJSONObject("links"), oldUri, uri, s.getString("sha"), s.getString("sha"), s.getString("name"), s.getLong("bytes"))
        }
        val row = dao.byId(id) ?: error("Запись истории не подтверждена")
        check(row.savedUri == uri && !row.hidden && !row.isPrivate && row.state == DownloadState.COMPLETED && dao.countSavedUriReferences(uri) == 1)
        return row
    }
    private suspend fun advance(batch: JSONObject, progress: (String) -> Unit): String {
        val items = batch.getJSONArray("items")
        try {
            while (batch.getInt("cursor") < items.length()) {
                coroutineContext.ensureActive(); links.ensureIdle()
                val index = batch.getInt("cursor"); val op = items.getJSONObject(index); val s = op.getJSONObject("snapshot")
                val type = MediaType.valueOf(s.getString("type")); val uri = Uri.parse(s.getString("uri")); val file = backupFile(batch, index)
                progress("${index + 1}/${items.length()} · ${s.getString("name")}")
                if (op.getString("stage") == "PREPARED") {
                    check(sameSnapshot(s, media(uri, type), OldDownloadsPolicy.SOURCE)) { "Исходный снимок изменился" }
                    verifyReferencesBefore(op); backup(s, file)
                    op.put("stage", "MOVE_INTENT"); write(journal, batch)
                }
                if (op.getString("stage") == "MOVE_INTENT") withContext(NonCancellable) {
                    backup(s, file)
                    move(s, type)
                    op.put("stage", "MOVED"); write(journal, batch)
                }
                if (op.getString("stage") == "MOVED") withContext(NonCancellable) {
                    check(sameSnapshot(s, media(uri, type), OldDownloadsPolicy.target(type))) { "Проверка перемещённого файла не прошла" }
                    val row = bind(op)
                    check(sameSnapshot(s, media(uri, type), OldDownloadsPolicy.target(type))) { "Проверка после истории не прошла" }
                    if (batch.getBoolean("trial")) write(receipt, JSONObject().put("version", 1).put("snapshot", s)
                        .put("historyId", row.id).put("historySnapshot", rowJson(row).toString()).put("confirmed", false))
                    op.put("stage", "DONE"); write(journal, batch)
                }
                check(op.getString("stage") == "DONE") { "Неизвестный этап журнала" }
                batch.put("cursor", index + 1); write(journal, batch)
                file.delete()
            }
            val result = "Перенесено: ${items.length()}. Даты, SHA-256 и история подтверждены." +
                if (batch.getBoolean("trial")) " Проверка положения в Xiaomi — до открытия массового режима." else ""
            batch.put("done", true).put("message", result); write(journal, batch); progress(result); return result
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val result = "Перенос остановлен: ${e.message ?: e.javaClass.simpleName}. Журнал сохранён."
            batch.put("message", result); write(journal, batch); progress(result); return result
        }
    }
}
