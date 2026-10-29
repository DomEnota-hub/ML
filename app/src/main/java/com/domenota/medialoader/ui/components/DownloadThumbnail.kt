package com.domenota.medialoader.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import com.domenota.medialoader.data.preferences.VideoPreferences
import com.domenota.medialoader.data.preferences.VideoThumbnailMode
import com.domenota.medialoader.data.preview.RemoteImageLoader
import com.domenota.medialoader.data.preview.preferredVideoThumbnail
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.storage.AudioThumbnailInvalidation
import com.domenota.medialoader.ui.model.MediaKind
import java.io.File
import java.io.FileOutputStream
import android.util.LruCache
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import kotlin.math.max
import kotlin.math.roundToInt

private val thumbnailMemory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}
private val thumbnailGate = Semaphore(2)
private val sourceThumbnailGate = Semaphore(2)
private val thumbnailLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
private val lastThumbnailPrune = java.util.concurrent.atomic.AtomicLong(0L)
private fun memoryKey(uri: String, kind: MediaKind): String =
    "$kind|$uri|${if (kind == MediaKind.AUDIO) AudioThumbnailInvalidation.revision(uri) else 0L}"

internal suspend fun prepareDownloadThumbnail(context: Context, uri: String, kind: MediaKind, previewUrl: String? = null): Bitmap? =
    if (kind == MediaKind.VIDEO) withContext(Dispatchers.IO) {
        preferredVideoThumbnail(VideoPreferences(context).thumbnailMode, previewUrl,
            loadSource = { sourceThumbnailGate.withPermit { RemoteImageLoader.fetch(context, it, maxDimension = MAX_THUMBNAIL_SIDE) } },
            loadFrame = { prepareFrameThumbnail(context, uri, kind) })
    } else prepareFrameThumbnail(context, uri, kind)

private suspend fun prepareFrameThumbnail(context: Context, uri: String, kind: MediaKind): Bitmap? {
    val key = memoryKey(uri, kind)
    thumbnailMemory.get(key)?.let { return it }
    return withContext(Dispatchers.IO) {
        thumbnailLocks.computeIfAbsent(key) { Mutex() }.withLock {
            thumbnailMemory.get(key) ?: thumbnailGate.withPermit {
                loadCachedOrCreateThumbnail(context, uri, kind)?.also { thumbnailMemory.put(key, it) }
            }
        }
    }
}

/**
 * Audio/photos prefer local thumbnails. Videos follow the source-cover/frame preference;
 * source covers use the existing persistent image cache, with lazy local-frame fallback.
 */
@Composable
fun DownloadThumbnail(
    savedUri: String?,
    previewUrl: String?,
    kind: MediaKind,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings = remember(context) { VideoPreferences(context) }
    var thumbnailMode by remember(settings) { mutableStateOf(settings.thumbnailMode) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == VideoPreferences.THUMBNAIL_KEY || key == null) thumbnailMode = settings.thumbnailMode
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val sourceFirst = kind == MediaKind.VIDEO && thumbnailMode == VideoThumbnailMode.SOURCE
    val revisions by AudioThumbnailInvalidation.revisions.collectAsState()
    val revision = if (kind == MediaKind.AUDIO && savedUri != null)
        revisions[savedUri] ?: 0L else 0L
    var local by remember(savedUri, kind, revision, sourceFirst, previewUrl) {
        mutableStateOf(if (sourceFirst) null else savedUri?.let { thumbnailMemory.get(memoryKey(it, kind)) })
    }
    var localReady by remember(savedUri, kind, revision, sourceFirst, previewUrl) { mutableStateOf(savedUri == null) }

    LaunchedEffect(savedUri, kind, revision, sourceFirst, previewUrl) {
        localReady = false
        local = if (savedUri == null) {
            null
        } else {
            prepareDownloadThumbnail(context, savedUri, kind, previewUrl)
        }
        localReady = true
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        if (kind == MediaKind.AUDIO) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null,
                modifier = Modifier.size(38.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        if (local != null) {
            Image(
                bitmap = local!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (localReady) {
            RemotePreview(previewUrl, Modifier.fillMaxSize())
        }
    }
}

private fun loadCachedOrCreateThumbnail(
    context: Context,
    savedUri: String,
    kind: MediaKind,
): Bitmap? {
    val cacheFile = thumbnailFile(context, savedUri, kind)
    if (cacheFile.exists() && cacheFile.length() > 0L) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(cacheFile.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_THUMBNAIL_SIDE * 2) sample *= 2
        BitmapFactory.decodeFile(cacheFile.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample })?.let {
            cacheFile.setLastModified(System.currentTimeMillis())
            return it
        }
        cacheFile.delete()
    }

    val saved = Uri.parse(savedUri)
    val source = decodeLocalThumbnail(context, saved, kind) ?: run {
        AppLog.verboseSampled("Thumbnail", "local-missing:${kind.name}",
            "Local preview unavailable · kind=${kind.name} · scheme=${saved.scheme.orEmpty()} · remoteFallback=true")
        return null
    }
    val thumb = scaleThumbnail(source, MAX_THUMBNAIL_SIDE)
    if (thumb !== source) source.recycle()

    runCatching {
        cacheFile.parentFile?.mkdirs()
        val temporary = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        FileOutputStream(temporary).use { stream ->
            check(thumb.compress(Bitmap.CompressFormat.JPEG, 88, stream))
        }
        if (!temporary.renameTo(cacheFile)) {
            cacheFile.delete()
            temporary.copyTo(cacheFile, overwrite = true)
            temporary.delete()
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastThumbnailPrune.get()
        if ((previous == 0L || now - previous >= 60_000L) &&
            lastThumbnailPrune.compareAndSet(previous, now)) pruneLocalThumbnails(cacheFile.parentFile)
    }
    return thumb
}

/** Keep a bounded local history cache; saved photos and videos are never touched. */
private fun pruneLocalThumbnails(directory: File?) {
    val entries = directory?.listFiles()?.filter { it.isFile && it.extension == "jpg" } ?: return
    var total = entries.sumOf { it.length() }
    if (total <= 80L * 1024 * 1024) return
    for (entry in entries.sortedBy { it.lastModified() }) {
        if (total <= 64L * 1024 * 1024) break
        total -= entry.length()
        entry.delete()
    }
}

private fun decodeLocalThumbnail(
    context: Context,
    uri: Uri,
    kind: MediaKind,
): Bitmap? = runCatching {
    when (kind) {
        MediaKind.VIDEO -> {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val scaled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        1_500_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        MAX_THUMBNAIL_SIDE,
                        MAX_THUMBNAIL_SIDE,
                    )
                } else null
                scaled ?: retriever.getFrameAtTime(
                    1_500_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: retriever.getFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
            } finally {
                retriever.release()
            }
        }
        MediaKind.AUDIO -> {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture?.let { bytes ->
                    decodeSampledBytes(bytes, MAX_DECODE_SIDE)
                }
            } finally {
                retriever.release()
            }
        }
        MediaKind.IMAGE -> decodeSampledUri(context, uri, MAX_DECODE_SIDE)
    }
}.onFailure { error ->
    AppLog.verboseSampled("Thumbnail", "decode-error:${kind.name}",
        "Local thumbnail decode failed · kind=${kind.name} · error=${error.javaClass.simpleName}")
}.getOrNull()

private fun decodeSampledUri(context: Context, uri: Uri, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(
            it,
            null,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}

private fun decodeSampledBytes(bytes: ByteArray, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

private fun scaleThumbnail(bitmap: Bitmap, maxSide: Int): Bitmap {
    val longest = max(bitmap.width, bitmap.height)
    if (longest <= maxSide || longest <= 0) return bitmap
    val scale = maxSide.toFloat() / longest
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).roundToInt().coerceAtLeast(1),
        (bitmap.height * scale).roundToInt().coerceAtLeast(1),
        true,
    )
}

private fun thumbnailFile(context: Context, savedUri: String, kind: MediaKind): File {
    if (kind == MediaKind.AUDIO) return AudioThumbnailInvalidation.file(context.filesDir, savedUri)
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("${kind.name}|v2|$savedUri".toByteArray())
        .joinToString("") { "%02x".format(it) }
    return File(File(context.filesDir, "thumbnails"), "$digest.jpg")
}

private const val MAX_DECODE_SIDE = 1024
private const val MAX_THUMBNAIL_SIDE = 320
