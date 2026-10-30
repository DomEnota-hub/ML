package com.domenota.medialoader.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import com.domenota.medialoader.data.playback.AudioWaveformPrecompute

@Composable
fun VideoTimelineStrip(
    uri: Uri,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var frames by remember(uri, durationMs) { mutableStateOf<List<Bitmap>>(emptyList()) }

    LaunchedEffect(uri, durationMs) {
        if (durationMs <= 0L) return@LaunchedEffect
        frames = withContext(Dispatchers.IO) {
            loadVideoTimelineFrames(context, uri, durationMs, VIDEO_TIMELINE_FRAMES)
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.18f)),
    ) {
        if (frames.isNotEmpty()) {
            Row(Modifier.fillMaxSize()) {
                frames.forEach { frame ->
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
fun VideoScrubFrame(
    uri: Uri,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val provider = remember(uri) { VideoFrameProvider(context.applicationContext, uri) }
    val requestMs = remember(positionMs) { (positionMs / SCRUB_FRAME_STEP_MS) * SCRUB_FRAME_STEP_MS }
    var frame by remember(uri) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(provider) {
        onDispose { provider.close() }
    }

    LaunchedEffect(provider, requestMs) {
        frame = try {
            withContext(Dispatchers.IO) { provider.frameAt(requestMs) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    frame?.let { image ->
        Box(
            modifier
                .size(width = 132.dp, height = 78.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
fun AudioWaveformTimeline(
    uri: Uri,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var waveform by remember(uri, durationMs) { mutableStateOf<List<Float>>(emptyList()) }

    LaunchedEffect(uri, durationMs) {
        if (durationMs <= 0L) return@LaunchedEffect
        // Editor NEVER decodes an uncached audio file or blocks on another decoder.
        // Only read a completed atomic cache and request a priority background job.
        val saved = withContext(Dispatchers.IO) {
            readCachedAudioWaveform(context, uri, durationMs)
        }
        if (saved != null) {
            waveform = saved
        } else {
            AudioWaveformPrecompute.requestImmediate(context, uri, durationMs)
            // WorkManager may be deferred by Android; UI stays responsive while it runs.
            repeat(240) {
                delay(1000L)
                val ready = withContext(Dispatchers.IO) {
                    readCachedAudioWaveform(context, uri, durationMs)
                }
                if (ready != null) {
                    waveform = ready
                    return@LaunchedEffect
                }
            }
        }
    }

    val barColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
            ),
    ) {
        if (waveform.isEmpty()) return@Canvas
        // AIMP-like: individual narrow vertical strokes, almost touching at phone scale.
        // Never fill a continuous polygon; preserve actual amplitude differences.
        val columns = (size.width / 1.35f).toInt().coerceIn(1, waveform.size)
        val amplitudes = waveformColumnPeaks(waveform, columns)
        val center = size.height / 2f
        val slot = size.width / columns
        val stroke = (slot * 0.84f).coerceIn(0.85f, 1.6f)
        amplitudes.forEachIndexed { index, raw ->
            val half = raw.coerceIn(0f, 1f) * size.height * 0.46f
            val x = (index + 0.5f) * slot
            drawLine(
                color = barColor,
                start = androidx.compose.ui.geometry.Offset(x, center - half),
                end = androidx.compose.ui.geometry.Offset(x, center + half),
                strokeWidth = stroke,
            )
        }
    }
}

/** One non-overlapping window per visible column; preserves transients when resized. */
internal fun waveformColumnPeaks(peaks: List<Float>, columns: Int): FloatArray {
    require(columns > 0)
    if (peaks.isEmpty()) return FloatArray(columns)
    return FloatArray(columns) { column ->
        val first = column * peaks.size / columns
        val lastExclusive = ((column + 1) * peaks.size / columns).coerceAtLeast(first + 1)
        var peak = 0f
        var sum = 0f
        var count = 0
        for (index in first until lastExclusive.coerceAtMost(peaks.size)) {
            val value = peaks[index].coerceIn(0f, 1f)
            peak = max(peak, value)
            sum += value
            count++
        }
        // Average body level, retain a small contribution from brief transients.
        // The old peak-only reduction made every stroke close to full height.
        if (count == 0) 0f else (sum / count * 0.85f + peak * 0.15f).coerceIn(0f, 1f)
    }
}

private class VideoFrameProvider(
    private val context: Context,
    private val uri: Uri,
) : AutoCloseable {
    private var retriever: MediaMetadataRetriever? = null

    @Synchronized
    fun frameAt(positionMs: Long): Bitmap? {
        val active = retriever ?: MediaMetadataRetriever().also {
            it.setDataSource(context, uri)
            retriever = it
        }
        val source = active.getFrameAtTime(
            positionMs.coerceAtLeast(0L) * 1_000L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        ) ?: return null
        val scaled = source.scaledDown(MAX_VIDEO_PREVIEW_SIDE)
        if (scaled !== source) source.recycle()
        return scaled
    }

    @Synchronized
    override fun close() {
        retriever?.release()
        retriever = null
    }
}

private fun loadVideoTimelineFrames(
    context: Context,
    uri: Uri,
    durationMs: Long,
    count: Int,
): List<Bitmap> {
    val cache = File(context.filesDir, "timeline/video/${cacheKey(uri.toString())}")
    cache.mkdirs()

    val cached = (0 until count).mapNotNull { index ->
        val file = File(cache, "$index.jpg")
        if (file.isFile && file.length() > 0L) BitmapFactory.decodeFile(file.absolutePath) else null
    }
    if (cached.size == count) return cached

    cache.listFiles()?.forEach { it.delete() }
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        (0 until count).mapNotNull { index ->
            val positionMs = if (count <= 1) 0L else {
                ((durationMs - 1L).coerceAtLeast(0L) * index) / (count - 1L)
            }
            retriever.getFrameAtTime(
                positionMs * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )?.let { source ->
                val scaled = source.scaledDown(MAX_VIDEO_TIMELINE_SIDE)
                if (scaled !== source) source.recycle()
                runCatching {
                    File(cache, "$index.jpg").outputStream().use {
                        scaled.compress(Bitmap.CompressFormat.JPEG, 76, it)
                    }
                }
                scaled
            }
        }
    } finally {
        retriever.release()
    }
}

private val audioCacheLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

/** Content and file updates invalidate a saved waveform, even when their URI is unchanged. */
internal fun waveformCacheIdentity(uri: String, durationMs: Long, size: Long, modified: Long, buckets: Int): String =
    "v6|$uri|$durationMs|$size|$modified|$buckets"

private fun audioWaveformCacheFile(context: Context, uri: Uri, durationMs: Long, buckets: Int): File {
    var bytes = -1L
    var modified = -1L
    if (uri.scheme == "file") {
        val file = uri.path?.let(::File)
        if (file != null) { bytes = file.length(); modified = file.lastModified() }
    } else if (uri.scheme == "content") {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.MediaStore.MediaColumns.SIZE,
                    android.provider.MediaStore.MediaColumns.DATE_MODIFIED),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeCol = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.SIZE)
                    val modifiedCol = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
                    if (sizeCol >= 0 && !cursor.isNull(sizeCol)) bytes = cursor.getLong(sizeCol)
                    if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) modified = cursor.getLong(modifiedCol)
                }
            }
        }
    }
    val identity = waveformCacheIdentity(uri.toString(), durationMs, bytes, modified, buckets)
    return File(context.filesDir, "timeline/audio/${cacheKey(identity)}.wave")
}

/** Read-only UI path: never decode or wait for a decoder on the UI coroutine. */
internal fun readCachedAudioWaveform(
    context: Context, uri: Uri, durationMs: Long,
    bucketCount: Int = AUDIO_WAVEFORM_BUCKETS,
): List<Float>? = readAudioWaveformCacheFile(audioWaveformCacheFile(context, uri, durationMs, bucketCount), bucketCount)

private fun readAudioWaveformCacheFile(cache: File, bucketCount: Int): List<Float>? {
    if (!cache.isFile) return null
    return runCatching {
        cache.readText().split(',').mapNotNull(String::toFloatOrNull)
            .takeIf { it.size == bucketCount && it.all { value -> value.isFinite() && value in 0f..1f } }
    }.getOrNull()
}

internal fun hasCachedAudioWaveform(context: Context, uri: Uri, durationMs: Long): Boolean =
    readCachedAudioWaveform(context, uri, durationMs) != null

/** Only the background worker calls this. Per-key locks prevent duplicate decoding. */
internal fun loadAudioWaveform(
    context: Context,
    uri: Uri,
    durationMs: Long,
    bucketCount: Int = AUDIO_WAVEFORM_BUCKETS,
): List<Float> {
    val cache = audioWaveformCacheFile(context, uri, durationMs, bucketCount)
    val lock = audioCacheLocks.computeIfAbsent(cache.name) { Any() }
    return synchronized(lock) {
        readAudioWaveformCacheFile(cache, bucketCount)?.let { return@synchronized it }
        val decoded = decodeAudioWaveform(context, uri, durationMs, bucketCount)
        if (decoded.size == bucketCount && decoded.any { it > 0f }) {
            runCatching {
                cache.parentFile?.mkdirs()
                val temporary = File.createTempFile("waveform-", ".tmp", cache.parentFile)
                try {
                    temporary.writeText(decoded.joinToString(","))
                    check(temporary.renameTo(cache))
                } finally {
                    temporary.delete()
                }
            }
        }
        decoded
    }
}

private fun decodeAudioWaveform(
    context: Context,
    uri: Uri,
    durationMs: Long,
    bucketCount: Int,
): List<Float> {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    return try {
        extractor.setDataSource(context, uri, null)
        var audioTrack = -1
        var inputFormat: MediaFormat? = null
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                audioTrack = index
                inputFormat = format
                break
            }
        }
        if (audioTrack < 0 || inputFormat == null) return emptyList()
        extractor.selectTrack(audioTrack)

        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()
        val decoder = MediaCodec.createDecoderByType(mime)
        codec = decoder
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        val durationUs = (
            durationMs.takeIf { it > 0L }?.times(1_000L)
                ?: inputFormat.getLongOrNull(MediaFormat.KEY_DURATION)
                ?: 1L
            ).coerceAtLeast(1L)
        val energy = DoubleArray(bucketCount)
        val sampleCounts = LongArray(bucketCount)
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        while (!outputEnded) {
            if (!inputEnded) {
                val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inputIndex)
                    if (buffer != null) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                size,
                                extractor.sampleTime.coerceAtLeast(0L),
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }
            }

            when (val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val output = decoder.outputFormat
                    pcmEncoding = output.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING)
                        ?: AudioFormat.ENCODING_PCM_16BIT
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (outputIndex >= 0) {
                    if (info.size > 0) {
                        decoder.getOutputBuffer(outputIndex)?.let { buffer ->
                            val part = pcmEnergy(buffer, info.offset, info.size, pcmEncoding)
                            val bucket = (
                                info.presentationTimeUs.coerceAtLeast(0L) * bucketCount / durationUs
                                ).toInt().coerceIn(0, bucketCount - 1)
                            energy[bucket] += part.total
                            sampleCounts[bucket] += part.count
                        }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }

        // Average sound energy (RMS) preserves dynamics on compressed modern music.
        // The previous maximum-sample approach made nearly every column full height.
        val rms = FloatArray(bucketCount) { index ->
            if (sampleCounts[index] == 0L) 0f
            else sqrt(energy[index] / sampleCounts[index]).toFloat()
        }
        normalizeWaveformRms(rms)
    } catch (_: Exception) {
        emptyList()
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        extractor.release()
    }
}

/**
 * Calm amplitude calibration for the gallery editor, similar in dynamic range to AIMP:
 * quieter passages remain visibly shorter; mastered peaks no longer saturate the strip.
 * Uses a robust high percentile but intentionally does NOT apply a square-root loudness boost.
 */
internal fun normalizeWaveformRms(rms: FloatArray): List<Float> {
    val active = rms.filter { it.isFinite() && it > 0f }.sorted()
    if (active.isEmpty()) return emptyList()
    val reference = active[kotlin.math.ceil(active.lastIndex * 0.985f).toInt()].coerceAtLeast(0.0001f)
    val calibrated = rms.map { value ->
        if (!value.isFinite() || value <= 0f) 0f
        else (0.92f * (value / reference).coerceIn(0f, 1f).pow(1.08f)).coerceIn(0f, 0.92f)
    }
    // Light three-bin low-pass smoothing softens spiky edges without flattening
    // actual changes of loudness. Keep digital silence silent.
    return calibrated.indices.map { index ->
        if (calibrated[index] <= 0f) 0f
        else {
            val previous = calibrated[(index - 1).coerceAtLeast(0)]
            val next = calibrated[(index + 1).coerceAtMost(calibrated.lastIndex)]
            (previous * 0.25f + calibrated[index] * 0.5f + next * 0.25f)
                .coerceIn(0f, 0.92f)
        }
    }
}

internal data class PcmEnergy(val total: Double, val count: Long)

/** Accumulate squared PCM samples; RMS is evaluated over actual decoded audio. */
internal fun pcmEnergy(
    source: ByteBuffer,
    offset: Int,
    size: Int,
    encoding: Int,
): PcmEnergy {
    if (size <= 0) return PcmEnergy(0.0, 0)
    val buffer = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    buffer.position(offset.coerceIn(0, buffer.limit()))
    buffer.limit((offset + size).coerceIn(buffer.position(), buffer.limit()))
    var energy = 0.0
    var count = 0L
    fun accumulate(value: Double) {
        energy += value * value
        count++
    }
    when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> {
            while (buffer.remaining() >= 4) {
                accumulate(buffer.float.toDouble().coerceIn(-1.0, 1.0))
            }
        }
        AudioFormat.ENCODING_PCM_8BIT -> {
            while (buffer.hasRemaining()) {
                accumulate(((buffer.get().toInt() and 0xFF) - 128) / 128.0)
            }
        }
        else -> {
            while (buffer.remaining() >= 2) {
                accumulate(buffer.short.toDouble() / 32768.0)
            }
        }
    }
    return PcmEnergy(energy, count)
}

private fun Bitmap.scaledDown(maxSide: Int): Bitmap {
    val longest = max(width, height)
    if (longest <= maxSide || longest <= 0) return this
    val factor = maxSide.toFloat() / longest
    return Bitmap.createScaledBitmap(
        this,
        (width * factor).toInt().coerceAtLeast(1),
        (height * factor).toInt().coerceAtLeast(1),
        true,
    )
}

private fun MediaFormat.getLongOrNull(key: String): Long? =
    if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

private fun MediaFormat.getIntegerOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun cacheKey(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

private const val VIDEO_TIMELINE_FRAMES = 8
internal const val AUDIO_WAVEFORM_BUCKETS = 1024
private const val MAX_VIDEO_TIMELINE_SIDE = 360
private const val MAX_VIDEO_PREVIEW_SIDE = 720
private const val SCRUB_FRAME_STEP_MS = 250L
private const val CODEC_TIMEOUT_US = 10_000L
