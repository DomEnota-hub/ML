package com.domenota.medialoader.data.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.domenota.medialoader.core.database.CatalogDao
import com.domenota.medialoader.core.database.DownloadDao
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * Stage 1 only. NO MediaStore insert/update/delete, no file writes, no Room writes.
 * Reads *all* download rows, counts independent URI references and builds an
 * in-memory report. JSON is saved only by the user's explicit SAF export action.
 * Full file SHA-256 and media playback are NOT verified by this inventory.
 */
internal class MediaMigrationReadOnlyInventory(
    private val context: Context,
    private val downloads: DownloadDao,
    private val catalog: CatalogDao,
) {
    data class Result(val summary: String, val json: String)
    private val resolver get() = context.contentResolver
    private val policy get() = MigrationPreflightPolicy

    private data class AndroidMedia(
        val id: Long,
        val owner: String?,
        val path: String?,
        val name: String?,
        val mime: String?,
        val bytes: Long,
        val added: Long,
        val taken: Long,
        val modified: Long,
        val pendingOrTrashed: Boolean,
        val canRead: Boolean,
    )

    private fun optionalString(row: JSONObject, key: String): String? =
        if (row.isNull(key)) null else row.optString(key)

    private fun readDeviceCatalog(): Map<String, Int> {
        // Get only existing references; never create a catalog entry or prune an alias.
        val raw = context.getSharedPreferences("external_media_catalog", Context.MODE_PRIVATE)
            .getString("catalog", null) ?: return emptyMap()
        val root = JSONObject(raw)
        require(root.getInt("version") == 1) { "Неизвестный формат внешнего каталога" }
        val records = root.getJSONArray("entries")
        val refs = mutableMapOf<String, Int>()
        for (i in 0 until records.length()) {
            val uri = optionalString(records.getJSONObject(i), "uri")
                ?.takeIf { it.isNotBlank() } ?: continue
            refs[uri] = (refs[uri] ?: 0) + 1
        }
        return refs
    }

    private fun readVkReferences(): Map<String, Int> {
        // SQLiteOpenHelper.readableDatabase may CREATE a missing DB. Do not use it:
        // this audit must not generate any application data as a side effect.
        val path = context.getDatabasePath("vk-community-images.db")
        if (!path.isFile) return emptyMap()
        val refs = mutableMapOf<String, Int>()
        val db = SQLiteDatabase.openDatabase(path.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        try {
            for (table in listOf("images", "hashes")) {
                db.rawQuery("SELECT saved_uri FROM $table WHERE saved_uri IS NOT NULL", null)
                    .use { cursor ->
                        while (cursor.moveToNext()) {
                            val uri = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                            refs[uri] = (refs[uri] ?: 0) + 1
                        }
                    }
            }
        } finally { db.close() }
        return refs
    }

    private fun mediaSnapshot(uri: Uri): AndroidMedia? {
        val columns = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.IS_PENDING,
        )
        if (Build.VERSION.SDK_INT >= 30) columns += MediaStore.MediaColumns.IS_TRASHED
        val raw = resolver.query(uri, columns.toTypedArray(), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            fun str(key: String): String? {
                val i = cursor.getColumnIndex(key)
                return if (i < 0 || cursor.isNull(i)) null else cursor.getString(i)
            }
            fun num(key: String): Long {
                val i = cursor.getColumnIndex(key)
                return if (i < 0 || cursor.isNull(i)) 0L else cursor.getLong(i)
            }
            AndroidMedia(
                id = num(MediaStore.MediaColumns._ID),
                owner = str(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                path = str(MediaStore.MediaColumns.RELATIVE_PATH),
                name = str(MediaStore.MediaColumns.DISPLAY_NAME),
                mime = str(MediaStore.MediaColumns.MIME_TYPE),
                bytes = num(MediaStore.MediaColumns.SIZE),
                added = num(MediaStore.MediaColumns.DATE_ADDED) * 1000L,
                taken = num(MediaStore.MediaColumns.DATE_TAKEN),
                modified = num(MediaStore.MediaColumns.DATE_MODIFIED) * 1000L,
                pendingOrTrashed = num(MediaStore.MediaColumns.IS_PENDING) != 0L ||
                    (Build.VERSION.SDK_INT >= 30 && num(MediaStore.MediaColumns.IS_TRASHED) != 0L),
                canRead = false,
            )
        } ?: return null
        // Open an FD read-only. This does not read all bytes or mutate the file.
        val readable = try {
            resolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) { false }
        return raw.copy(canRead = readable)
    }

    suspend fun inspect(): Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val rows = downloads.all() // includes hidden and private for COUNTING, not scanning.
        val refs = mutableMapOf<String, Int>()
        rows.forEach { row ->
            row.savedUri?.let { refs[it] = (refs[it] ?: 0) + 1 }
            row.privateSourceUri?.let { refs[it] = (refs[it] ?: 0) + 1 }
        }
        val warnings = mutableListOf<String>()
        val vk = try { readVkReferences() } catch (error: Exception) {
            warnings += "VK_INDEX_UNAVAILABLE: ${error.javaClass.simpleName}"
            emptyMap()
        }
        val external = try { readDeviceCatalog() } catch (error: Exception) {
            warnings += "DEVICE_CATALOG_UNAVAILABLE: ${error.javaClass.simpleName}"
            emptyMap()
        }
        val auxiliaryReadable = warnings.none {
            it.startsWith("VK_INDEX_UNAVAILABLE") || it.startsWith("DEVICE_CATALOG_UNAVAILABLE")
        }
        val assignments = try { catalog.observeAssignments().first().associateBy { it.downloadId } }
        catch (error: Exception) {
            warnings += "HISTORY_CATALOG_UNAVAILABLE: ${error.javaClass.simpleName}"
            emptyMap()
        }
        val completeIndexes = auxiliaryReadable && warnings.none { it.startsWith("HISTORY_CATALOG_UNAVAILABLE") }
        val results = JSONArray()
        val counters = sortedMapOf<String, Int>()
        val blockers = sortedMapOf<String, Int>()
        var candidatesBytes = 0L
        var candidates = 0
        var sourceLinked = 0
        var albumAssignments = 0
        var favorites = 0
        var completedMedia = 0
        var unsupportedFormats = 0
        for (row in rows) {
            coroutineContext.ensureActive()
            if (row.state == DownloadState.COMPLETED &&
                row.mediaType in setOf(MediaType.PHOTO, MediaType.VIDEO) &&
                !row.hidden && !row.isPrivate) completedMedia++
            if (!row.sourceUrl.isNullOrBlank() || !row.sourcePageUrl.isNullOrBlank()) sourceLinked++
            assignments[row.id]?.let { a ->
                if (a.folderId != null) albumAssignments++
                if (a.isFavorite) favorites++
            }
            val uriString = row.savedUri
            val uri = uriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
            val mediaUri = uri?.scheme == "content" && uri.authority == MediaStore.AUTHORITY
            var media: AndroidMedia? = null
            var queryFailure: String? = null
            if (row.state == DownloadState.COMPLETED && !row.hidden && !row.isPrivate &&
                row.mediaType in setOf(MediaType.PHOTO, MediaType.VIDEO) && mediaUri &&
                Build.VERSION.SDK_INT >= 29) {
                media = try { mediaSnapshot(uri!!) }
                catch (error: Exception) { queryFailure = error.javaClass.simpleName; null }
            }
            val supported = when (row.mediaType) {
                MediaType.PHOTO -> media?.mime == "image/jpeg" &&
                    (media.name?.endsWith(".jpg", true) == true ||
                        media.name?.endsWith(".jpeg", true) == true)
                MediaType.VIDEO -> media?.mime == "video/mp4" &&
                    media.name?.endsWith(".mp4", true) == true
                else -> false
            }
            val sizeMatches = row.sizeBytes == null || row.sizeBytes <= 0L ||
                row.sizeBytes == media?.bytes
            val input = policy.Input(
                completed = row.state == DownloadState.COMPLETED,
                hidden = row.hidden,
                privateItem = row.isPrivate,
                photoOrVideo = row.mediaType in setOf(MediaType.PHOTO, MediaType.VIDEO),
                hasUri = !uriString.isNullOrBlank(),
                mediaStoreUri = mediaUri,
                validHistoryDate = row.createdAtEpochMillis in 946684800000L..now,
                uniqueHistoryUri = !uriString.isNullOrBlank() && refs[uriString] == 1,
                ownerMatches = media?.owner == context.packageName,
                correctPath = media?.path == "Pictures/MediaLoader/",
                nameMatches = media?.name == row.originalName,
                supportedMime = supported,
                positiveSize = media != null && media.bytes > 0L,
                sizeMatches = sizeMatches,
                pendingOrTrashed = media?.pendingOrTrashed ?: false,
                accessible = media?.canRead == true,
                vkReferences = vk[uriString] ?: 0,
                externalCatalogReferences = external[uriString] ?: 0,
                auxiliaryIndexesReadable = completeIndexes,
                addedMillis = media?.added ?: 0L,
                historyMillis = row.createdAtEpochMillis,
            )
            val reasons = policy.blockers(input).toMutableList()
            if (Build.VERSION.SDK_INT < 29) reasons += "ANDROID_BELOW_10"
            if (media == null && queryFailure != null) reasons += "MEDIA_QUERY_ERROR_${queryFailure}"
            if (!supported && media != null && row.state == DownloadState.COMPLETED &&
                !row.isPrivate && !row.hidden) unsupportedFormats++
            val decision = if (reasons.isNotEmpty()) "BLOCKED"
                else policy.decision(input)
            counters[decision] = (counters[decision] ?: 0) + 1
            reasons.forEach { blockers[it] = (blockers[it] ?: 0) + 1 }
            if (decision == policy.CANDIDATE) {
                candidates++
                candidatesBytes += media?.bytes ?: 0L
            }
            // Omit raw source URLs: they may contain auth tokens. The stable DB
            // ID, source-reference flags and URI are enough for next-stage QA.
            val record = JSONObject()
                .put("historyId", row.id)
                .put("mediaType", row.mediaType.name)
                .put("state", row.state.name)
                .put("hidden", row.hidden)
                .put("private", row.isPrivate)
                .put("historyTimestampIsQueueTime", true)
                .put("queueTimeMillis", row.createdAtEpochMillis)
                .put("hasSourceUrl", !row.sourceUrl.isNullOrBlank())
                .put("hasSourcePageUrl", !row.sourcePageUrl.isNullOrBlank())
                .put("hasBulkMediaKey", !row.bulkMediaKey.isNullOrBlank())
                .put("hasGroupId", !row.groupId.isNullOrBlank())
                .put("hasDerivedFromId", !row.derivedFromId.isNullOrBlank())
                .put("hasCatalogFolder", assignments[row.id]?.folderId != null)
                .put("isCatalogFavorite", assignments[row.id]?.isFavorite == true)
                .put("savedUri", if (row.isPrivate) JSONObject.NULL else uriString ?: JSONObject.NULL)
                .put("historyFilename", if (row.isPrivate) JSONObject.NULL else row.originalName)
                .put("historyBytes", row.sizeBytes ?: JSONObject.NULL)
                .put("historyUriReferences", refs[uriString] ?: 0)
                .put("vkIndexReferences", vk[uriString] ?: 0)
                .put("deviceCatalogReferences", external[uriString] ?: 0)
                .put("mediaStoreId", media?.id ?: JSONObject.NULL)
                .put("mediaStoreOwner", media?.owner ?: JSONObject.NULL)
                .put("mediaStoreFolder", media?.path ?: JSONObject.NULL)
                .put("mediaStoreFilename", media?.name ?: JSONObject.NULL)
                .put("mime", media?.mime ?: JSONObject.NULL)
                .put("mediaStoreBytes", media?.bytes ?: JSONObject.NULL)
                .put("dateAddedMillis", media?.added ?: JSONObject.NULL)
                .put("dateModifiedMillis", media?.modified ?: JSONObject.NULL)
                .put("dateTakenMillis", media?.taken ?: JSONObject.NULL)
                .put("readable", media?.canRead == true)
                .put("decision", decision)
                .put("blockers", JSONArray(reasons))
            results.put(record)
        }
        val storage = try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }
        catch (error: Exception) {
            warnings += "AVAILABLE_SPACE_UNKNOWN: ${error.javaClass.simpleName}"
            -1L
        }
        val root = JSONObject()
            .put("schemaVersion", 1)
            .put("auditGeneratedUtc", Instant.ofEpochMilli(now).toString())
            .put("mode", "READ_ONLY_NO_MEDIA_MUTATION")
            .put("metadataSource", "MediaStore + ML Room + read-only VK DB + device catalog")
            .put("historyTimestampWarning", "createdAtEpochMillis represents queue time, NOT verified download completion time")
            .put("noSha256Guarantee", "No file content hashes, codec checks or playback checks are done in the preflight")
            .put("massMigrationEnabled", false)
            .put("allRelatedIndexesReadable", completeIndexes)
            .put("summary", JSONObject()
                .put("allHistoryRows", rows.size)
                .put("completedPublicPhotosVideos", completedMedia)
                .put("sourceLinkedRows", sourceLinked)
                .put("catalogAssignedRows", albumAssignments)
                .put("favoriteRows", favorites)
                .put("migrationCandidatesForManualQA", candidates)
                .put("candidateTotalBytes", candidatesBytes)
                .put("availableBytes", storage)
                .put("candidateOneCopyNeedsBytesPlusReserve", candidatesBytes + 256L * 1024 * 1024)
                .put("unsupportedPublicMediaFormats", unsupportedFormats)
                .put("decisions", JSONObject(counters as Map<*, *>))
                .put("blockerFrequencies", JSONObject(blockers as Map<*, *>)))
            .put("warnings", JSONArray(warnings))
            .put("records", results)
        val text = "ПРОВЕРКА БЕЗ ИЗМЕНЕНИЙ: записей ${rows.size}; " +
            "фото/видео ${completedMedia}; кандидатов ${candidates}; " +
            "заблокировано ${counters["BLOCKED"] ?: 0}; " +
            "неоднозначные форматы ${unsupportedFormats}. " +
            "Связи: источники $sourceLinked, альбомы $albumAssignments, " +
            "избранное $favorites. " +
            "Свободно ${if (storage < 0) "неизвестно" else "${storage / 1024 / 1024} МБ"}; " +
            "кандидаты ${candidatesBytes / 1024 / 1024} МБ. " +
            "Индексы ${if (completeIndexes) "прочитаны" else "НЕ ПОЛНОСТЬЮ ДОСТУПНЫ"}. " +
            "Даты в истории = время постановки в очередь. SHA-256 ещё не сверялись. " +
            "Сохрани JSON-отчёт для разбора всех записей. Массовый перенос отключён."
        Result(text, root.toString(2))
    }
}
