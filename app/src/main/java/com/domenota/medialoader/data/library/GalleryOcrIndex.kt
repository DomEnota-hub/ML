package com.domenota.medialoader.data.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.BatteryManager
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.editor.LocalTextOcr
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import com.domenota.medialoader.data.storage.HistoricalRecoverySignals
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal object GalleryOcrPolicy {
    const val MAX_TEXT = 16384
    const val BATCH = 8
    fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(64) {
            val hex = "0123456789abcdef"
            bytes.forEach { b -> val n = b.toInt() and 255; append(hex[n ushr 4]); append(hex[n and 15]) }
        }
    }
    fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    fun publicPhoto(ref: LibraryMediaRef, blocked: Set<String>): Boolean =
        ref.item.mediaType == MediaType.PHOTO && ref.item.state == DownloadState.COMPLETED &&
            !ref.item.isPrivate && !ref.item.hidden && !ref.item.savedUri.isNullOrBlank() &&
            (ref.aliases + ref.key + ref.item.id + listOfNotNull(ref.appDownloadId, ref.appDownloadId?.let { "app:$it" }))
                .none(blocked::contains)
}

internal data class GalleryOcrCandidate(
    val ref: LibraryMediaRef, val key: String, val version: String,
    val stats: Set<String>, val providerModified: String?, val providerGeneration: String?,
) {
    fun same(other: LibraryMediaRef): Boolean = other.key == ref.key &&
        other.item.savedUri == ref.item.savedUri && other.item.sizeBytes == ref.item.sizeBytes &&
        GalleryOcrPolicy.publicPhoto(other, emptySet()) &&
        (stats.isEmpty() || other.aliases.any(stats::contains))
    fun admitted(context: Context, epoch: Long): Boolean =
        !HistoricalRecoverySignals.isRunning.value &&
            DuplicatePrivacyGuard.accepts(context, epoch, ref.appDownloadId ?: ref.key,
                ref.aliases + ref.key + ref.item.id)
    suspend fun stillSame(context: Context): Boolean = withContext(Dispatchers.IO) {
        val uri = requireNotNull(ref.item.savedUri)
        if (stats.isNotEmpty() && PublicMediaIdentity.capture(context, uri).none(stats::contains))
            return@withContext false
        if (providerModified == null && providerGeneration == null) return@withContext stats.isNotEmpty()
        val parsed = Uri.parse(uri)
        if (parsed.authority != "media") return@withContext false
        val columns = buildList {
            if (providerModified != null) add("date_modified")
            if (providerGeneration != null) add("generation_modified")
        }
        try {
            context.contentResolver.query(parsed, columns.toTypedArray(), null, null, null)?.use { cursor ->
                cursor.moveToFirst() && columns.all { name ->
                    val i = cursor.getColumnIndex(name)
                    i >= 0 && !cursor.isNull(i) &&
                        cursor.getLong(i).toString() == (if (name == "date_modified") providerModified else providerGeneration)
                }
            } == true
        } catch (_: Exception) { false }
    }
}

internal class GalleryOcrCatalog(refs: List<LibraryMediaRef>, fingerprints: Map<String, String>, blocked: Set<String>) {
    private val public = refs.filter { GalleryOcrPolicy.publicPhoto(it, blocked) }
    val candidates: List<GalleryOcrCandidate> = public.mapNotNull { ref ->
        val uri = requireNotNull(ref.item.savedUri)
        val stats = ref.aliases.filter { it.startsWith("file-stat:") }.toSet()
        val fingerprint = fingerprints[uri]
        val values = fingerprint?.split('\u0000').orEmpty()
        val provider = if (Uri.parse(uri).authority == "media" && values.size >= 12) values.takeLast(2) else emptyList()
        val modified = provider.getOrNull(0)?.takeIf { it.toLongOrNull() != null }
        val generation = provider.getOrNull(1)?.takeIf { it.toLongOrNull() != null }
        if (stats.isEmpty() && modified == null && generation == null) return@mapNotNull null
        val key = GalleryOcrPolicy.hash(ref.appDownloadId?.let { "app:$it" } ?: "phone:" + ref.key)
        val version = GalleryOcrPolicy.hash(listOf(uri, ref.item.sizeBytes, stats.sorted(), fingerprint).joinToString("\u0000"))
        GalleryOcrCandidate(ref, key, version, stats, modified, generation)
    }.groupBy { it.key }.values.mapNotNull { it.singleOrNull() }
    val byKey = candidates.associateBy { it.key }
    val unverified = public.size - candidates.size
}

/** Derived metadata in noBackup, with bounded text and transactional checkpoints.
 * The shared privacy lock also protects writes from concurrent private moves. */
internal class GalleryOcrIndex(context: Context, name: String = FILE) {
    private val context = context.applicationContext
    private val file = File(this.context.noBackupFilesDir, name)
    fun exists(): Boolean = file.exists()
    private fun open(): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null).also { db ->
        try {
            db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
            db.execSQL("CREATE TABLE IF NOT EXISTS image_ocr (k TEXT PRIMARY KEY, v TEXT NOT NULL, body TEXT NOT NULL)")
        } catch (failure: Throwable) { db.close(); throw failure }
    }
    private fun requireCurrent(epoch: Long, active: () -> Boolean) {
        check(active() && !HistoricalRecoverySignals.isRunning.value &&
            DuplicatePrivacyGuard.generation(context) == epoch) { "Доступ изменился" }
    }
    suspend fun reconcile(catalog: GalleryOcrCatalog, epoch: Long, active: () -> Boolean): Set<String> =
        withContext(Dispatchers.IO) {
            synchronized(DuplicatePrivacyGuard.lock) {
                requireCurrent(epoch, active)
                open().use { db ->
                    val ready = linkedSetOf<String>()
                    val stale = mutableListOf<String>()
                    db.rawQuery("SELECT k,v FROM image_ocr", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val key = cursor.getString(0)
                            val candidate = catalog.byKey[key]
                            if (candidate != null && candidate.version == cursor.getString(1) && candidate.admitted(context, epoch))
                                ready += key else stale += key
                        }
                    }
                    db.beginTransaction()
                    try {
                        stale.forEach { db.delete("image_ocr", "k=?", arrayOf(it)) }
                        requireCurrent(epoch, active)
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                    ready
                }
            }
        }
    suspend fun checkpoint(rows: List<Pair<GalleryOcrCandidate, String>>, epoch: Long, active: () -> Boolean): Set<String> =
        withContext(Dispatchers.IO) {
            synchronized(DuplicatePrivacyGuard.lock) {
                requireCurrent(epoch, active)
                val written = linkedSetOf<String>()
                open().use { db ->
                    db.beginTransaction()
                    try {
                        rows.forEach { (candidate, body) ->
                            if (candidate.admitted(context, epoch)) {
                                db.execSQL("INSERT OR REPLACE INTO image_ocr(k,v,body) VALUES(?,?,?)",
                                    arrayOf(candidate.key, candidate.version, GalleryOcrPolicy.normalized(body).take(GalleryOcrPolicy.MAX_TEXT)))
                                written += candidate.key
                            }
                        }
                        requireCurrent(epoch, active)
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
                written
            }
        }
    suspend fun search(catalog: GalleryOcrCatalog, query: String, epoch: Long, active: () -> Boolean): List<GalleryOcrCandidate> =
        withContext(Dispatchers.IO) {
            val term = GalleryOcrPolicy.normalized(query.take(512))
            if (term.isEmpty() || !file.exists()) return@withContext emptyList()
            synchronized(DuplicatePrivacyGuard.lock) {
                requireCurrent(epoch, active)
                if (!file.exists()) return@synchronized emptyList()
                open().use { db ->
                    db.rawQuery("SELECT k,v FROM image_ocr WHERE instr(body,?)>0", arrayOf(term)).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                val candidate = catalog.byKey[cursor.getString(0)]
                                if (candidate != null && candidate.version == cursor.getString(1) &&
                                    candidate.admitted(context, epoch)) add(candidate)
                            }
                        }
                    }
                }
            }
        }
    companion object {
        private const val FILE = "gallery-ocr-v1.db"
        private val privacyInvalidations = MutableStateFlow(0L)
        val invalidations = privacyInvalidations.asStateFlow()
        /** Synchronously discard derived text before a public file becomes private. */
        fun clearForPrivacy(context: Context) {
            val file = File(context.noBackupFilesDir, FILE)
            SQLiteDatabase.deleteDatabase(file)
            check(!file.exists() && !File(file.path + "-journal").exists() && !File(file.path + "-wal").exists()) {
                "Не удалось очистить публичный OCR-индекс"
            }
            privacyInvalidations.value++
        }
    }
}

internal object GalleryOcrReader {
    private const val EDGE = 2048
    fun charging(context: Context): Boolean = context.getSystemService(BatteryManager::class.java)?.isCharging == true
    suspend fun read(context: Context, candidate: GalleryOcrCandidate): String = withContext(Dispatchers.IO) {
        val bitmap = decode(context, Uri.parse(requireNotNull(candidate.ref.item.savedUri)))
        try {
            currentCoroutineContext().ensureActive()
            LocalTextOcr.readIndexPage(context, bitmap).text
        } finally { bitmap.recycle() }
    }
    private fun decode(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val w = info.size.width; val h = info.size.height
                require(w > 0 && h > 0)
                val scale = minOf(1.0, EDGE.toDouble() / maxOf(w, h))
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth > 0 && options.outHeight > 0)
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > EDGE) sample *= 2
        val raw = requireNotNull(resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        })
        val orientation = try { resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } } catch (_: Exception) { null }
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(-90f)
        }
        if (matrix.isIdentity) return raw
        return try { Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also {
            if (it !== raw) raw.recycle()
        } } catch (failure: Throwable) { raw.recycle(); throw failure }
    }
}
