package com.domenota.medialoader.ui.components

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt
import java.nio.ByteOrder
import java.io.File
import com.domenota.medialoader.core.logging.AppLog

private val waveformCaches = java.util.concurrent.ConcurrentHashMap<String,
    com.domenota.medialoader.data.playback.AudioWaveformCache>()

private fun waveformCache(context: Context) = waveformCaches.computeIfAbsent(context.filesDir.path) {
    com.domenota.medialoader.data.playback.AudioWaveformCache(File(context.filesDir, "waveforms"))
}

internal fun peekAudioWaveform(context: Context, uri: Uri): FloatArray? =
    waveformCache(context).peek(uri.toString())

internal fun loadAudioWaveform(context: Context, uri: Uri): FloatArray =
    waveformCache(context).get(uri.toString()) { decodeAudioPeaks(context, uri) }

/** Decodes the file in the background and groups PCM loudness by playback time. */
internal fun decodeAudioPeaks(context: Context, uri: Uri, count: Int = 256): FloatArray {
    val peaks = FloatArray(count)
    val squared = DoubleArray(count)
    val samples = LongArray(count)
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    try {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return peaks
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
        } else {
            // Some MP3 providers omit duration from the extractor format.
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                (retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 1L) * 1000L
            } finally {
                retriever.release()
            }
        }
        decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var emptyOutputCount = 0
        while (!outputEnded && emptyOutputCount < 300) {
            if (!inputEnded) {
                val index = decoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = decoder.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, 10_000)
            if (index >= 0) {
                emptyOutputCount = 0
                val buffer = decoder.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    val bucket = (info.presentationTimeUs * count / durationUs).toInt().coerceIn(0, count - 1)
                    while (buffer.remaining() >= 2) {
                        val sample = buffer.short.toDouble() / 32768.0
                        squared[bucket] += sample * sample
                        samples[bucket]++
                    }
                }
                decoder.releaseOutputBuffer(index, false)
                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            } else if (inputEnded) {
                emptyOutputCount++
            }
        }
    } catch (error: Exception) {
        AppLog.w("AudioWaveform", "Could not decode peaks · ${error.javaClass.simpleName}: ${error.message}")
    } finally {
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        extractor.release()
    }
    for (index in peaks.indices) {
        if (samples[index] > 0) peaks[index] = sqrt(squared[index] / samples[index]).toFloat()
    }
    return if (peaks.any { it > 0f }) shapeWaveform(peaks) else decodeCompressedSampleSizes(context, uri, count)
}

internal fun shapeWaveform(loudness: FloatArray): FloatArray {
    val nonzero = loudness.filter { it > 0f }.sorted()
    if (nonzero.isEmpty()) return loudness.copyOf()
    val low = nonzero[(nonzero.lastIndex * 0.1f).toInt()]
    val high = nonzero[(nonzero.lastIndex * 0.95f).toInt()]
    val spread = (high - low).coerceAtLeast(high * 0.15f).coerceAtLeast(0.0001f)
    return FloatArray(loudness.size) { index ->
        if (loudness[index] <= 0f) 0f
        else (0.2f + 0.8f * ((loudness[index] - low) / spread).coerceIn(0f, 1f))
    }
}

/** Last resort for formats without a usable PCM decoder; frame sizes still reveal variable bitrate. */
private fun decodeCompressedSampleSizes(context: Context, uri: Uri, count: Int): FloatArray {
    val peaks = FloatArray(count)
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return peaks
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val durationUs = format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
        val scratch = java.nio.ByteBuffer.allocate(256 * 1024)
        while (true) {
            scratch.clear()
            val bytes = extractor.readSampleData(scratch, 0)
            if (bytes < 0) break
            val bucket = (extractor.sampleTime * count / durationUs).toInt().coerceIn(0, count - 1)
            peaks[bucket] = maxOf(peaks[bucket], bytes.toFloat())
            extractor.advance()
        }
        val max = peaks.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        for (i in peaks.indices) peaks[i] = peaks[i] / max
    } catch (error: Exception) {
        AppLog.w("AudioWaveform", "Could not read frames · ${error.javaClass.simpleName}: ${error.message}")
    } finally {
        extractor.release()
    }
    return shapeWaveform(peaks)
}

@Composable
internal fun AudioWaveform(
    peaks: FloatArray,
    progress: Float,
    onSeekFraction: (Float) -> Unit,
) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    Canvas(Modifier.fillMaxWidth().height(62.dp)
        .pointerInput(onSeekFraction) {
            detectTapGestures { onSeekFraction((it.x / size.width).coerceIn(0f, 1f)) }
        }
        .pointerInput(onSeekFraction) {
            detectDragGestures { change, _ ->
                onSeekFraction((change.position.x / size.width).coerceIn(0f, 1f))
                change.consume()
            }
        }) {
        val columns = peaks.size.coerceAtLeast(1)
        val cellWidth = size.width / columns
        val gap = (cellWidth * 0.28f).coerceAtMost(2.dp.toPx())
        val barWidth = (cellWidth - gap).coerceAtLeast(1f)
        peaks.forEachIndexed { index, amplitude ->
            val height = size.height * (0.10f + 0.82f * amplitude.coerceIn(0f, 1f))
            val x = index * cellWidth + gap / 2f
            drawRect(
                color = if ((index + 0.5f) / columns <= progress) active else inactive,
                topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - height) / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
            )
        }
    }
}
