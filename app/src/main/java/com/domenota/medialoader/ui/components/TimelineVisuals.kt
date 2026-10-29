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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        waveform = withContext(Dispatchers.IO) {
            loadAudioWaveform(context, uri, durationMs, AUDIO_WAVEFORM_BUCKETS)
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
        val center = size.height / 2f
        val slot = size.width / waveform.size.coerceAtLeast(1)
        val stroke = max(1f, slot * 0.56f)
        waveform.forEachIndexed { index, raw ->
            val value = raw.coerceIn(0.04f, 1f)
            val half = value * size.height * 0.43f
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

private fun loadAudioWaveform(
    context: Context,
    uri: Uri,
    durationMs: Long,
    bucketCount: Int,
): List<Float> {
    val cache = File(context.filesDir, "timeline/audio/${cacheKey(uri.toString())}.wave")
    if (cache.isFile) {
        runCatching {
            cache.readText()
                .split(',')
                .mapNotNull(String::toFloatOrNull)
                .takeIf { it.size == bucketCount }
        }.getOrNull()?.let { return it }
    }

    val decoded = decodeAudioWaveform(context, uri, durationMs, bucketCount)
    if (decoded.isNotEmpty()) {
        runCatching {
            cache.parentFile?.mkdirs()
            cache.writeText(decoded.joinToString(","))
        }
    }
    return decoded
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
        val peaks = FloatArray(bucketCount)
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
                            val peak = pcmPeak(buffer, info.offset, info.size, pcmEncoding)
                            val bucket = (
                                info.presentationTimeUs.coerceAtLeast(0L) * bucketCount / durationUs
                                ).toInt().coerceIn(0, bucketCount - 1)
                            peaks[bucket] = max(peaks[bucket], peak)
                        }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }

        val maxPeak = peaks.maxOrNull()?.coerceAtLeast(0.0001f) ?: return emptyList()
        peaks.map { peak ->
            sqrt((peak / maxPeak).coerceIn(0f, 1f))
        }
    } catch (_: Exception) {
        emptyList()
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        extractor.release()
    }
}

private fun pcmPeak(
    source: ByteBuffer,
    offset: Int,
    size: Int,
    encoding: Int,
): Float {
    if (size <= 0) return 0f
    val buffer = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    buffer.position(offset.coerceIn(0, buffer.limit()))
    buffer.limit((offset + size).coerceIn(buffer.position(), buffer.limit()))
    var peak = 0f
    when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> {
            while (buffer.remaining() >= 4) {
                peak = max(peak, abs(buffer.float).coerceAtMost(1f))
            }
        }
        AudioFormat.ENCODING_PCM_8BIT -> {
            while (buffer.hasRemaining()) {
                peak = max(peak, abs((buffer.get().toInt() and 0xFF) - 128) / 128f)
            }
        }
        else -> {
            while (buffer.remaining() >= 2) {
                peak = max(peak, abs(buffer.short.toInt()) / 32768f)
            }
        }
    }
    return peak.coerceIn(0f, 1f)
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
private const val AUDIO_WAVEFORM_BUCKETS = 120
private const val MAX_VIDEO_TIMELINE_SIDE = 360
private const val MAX_VIDEO_PREVIEW_SIDE = 720
private const val SCRUB_FRAME_STEP_MS = 250L
private const val CODEC_TIMEOUT_US = 10_000L
