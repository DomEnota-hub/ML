package com.domenota.medialoader.ui.components

import android.media.AudioFormat
import java.nio.ByteBuffer
import kotlin.math.roundToLong

/** Editor-only viewport: selection and playback always remain absolute track times. */
internal object AudioEditorViewport {
    const val BASE_BUCKETS = 1024
    private const val MAX_ZOOM = 32
    fun detailBuckets(durationMs: Long): Int =
        durationMs.coerceIn(BASE_BUCKETS.toLong(), (BASE_BUCKETS * MAX_ZOOM).toLong()).toInt()
    fun maxZoom(durationMs: Long): Int {
        val available = detailBuckets(durationMs) / BASE_BUCKETS
        var zoom = 1
        while (zoom * 2 <= available && zoom < MAX_ZOOM) zoom *= 2
        return zoom
    }
    fun span(durationMs: Long, zoom: Int): Long =
        (durationMs.coerceAtLeast(0L) / zoom.coerceAtLeast(1)).coerceAtLeast(1L)
    fun clampStart(start: Long, durationMs: Long, spanMs: Long): Long =
        start.coerceIn(0L, (durationMs - spanMs).coerceAtLeast(0L))
    fun centeredStart(positionMs: Long, durationMs: Long, spanMs: Long): Long =
        clampStart(positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L)) - spanMs / 2, durationMs, spanMs)
    fun timeAt(x: Float, width: Float, start: Long, spanMs: Long, durationMs: Long): Long =
        (start + (x.coerceIn(0f, width.coerceAtLeast(1f)).toDouble() /
            width.coerceAtLeast(1f) * spanMs).roundToLong()).coerceIn(0L, durationMs.coerceAtLeast(0L))
    fun xFor(positionMs: Long, width: Float, start: Long, spanMs: Long): Float =
        ((positionMs - start).toDouble() / spanMs.coerceAtLeast(1L) * width).toFloat()
    fun pan(start: Long, deltaPx: Float, width: Float, spanMs: Long, durationMs: Long): Long =
        clampStart(start - (deltaPx.toDouble() / width.coerceAtLeast(1f) * spanMs).roundToLong(), durationMs, spanMs)

    /** Select only real decoded buckets within the viewport, never interpolate fake detail. */
    fun visiblePeaks(peaks: List<Float>, durationMs: Long, startMs: Long, endMs: Long): List<Float> {
        if (peaks.isEmpty() || durationMs <= 0L) return emptyList()
        val start = startMs.coerceIn(0L, durationMs)
        val end = endMs.coerceIn(start, durationMs)
        val first = (start.toDouble() / durationMs * peaks.size).toInt().coerceIn(0, peaks.lastIndex)
        val last = kotlin.math.ceil(end.toDouble() / durationMs * peaks.size).toInt().coerceIn(first + 1, peaks.size)
        return peaks.subList(first, last)
    }
}

/** Split a decoded PCM buffer at actual sample-frame time boundaries. */
internal fun accumulateEditorPcm(
    source: ByteBuffer, offset: Int, size: Int, encoding: Int,
    presentationTimeUs: Long, durationUs: Long, sampleRate: Int, channels: Int,
    energy: DoubleArray, counts: LongArray,
) {
    require(durationUs > 0L && sampleRate > 0 && channels > 0 && energy.isNotEmpty() && counts.size == energy.size)
    val sampleBytes = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> 2
    }
    val frameBytes = sampleBytes * channels
    val from = offset.coerceIn(0, source.limit())
    val available = size.coerceAtLeast(0).coerceAtMost(source.limit() - from)
    val frameCount = available / frameBytes
    val pts = presentationTimeUs.coerceAtLeast(0L)
    var frame = 0
    while (frame < frameCount) {
        val timeUs = pts + frame.toLong() * 1_000_000L / sampleRate
        val bucket = (timeUs.toDouble() / durationUs * energy.size).toInt().coerceIn(0, energy.lastIndex)
        val nextFrame = if (bucket == energy.lastIndex) frameCount else {
            val boundaryUs = ((bucket + 1L) * durationUs + energy.size - 1L) / energy.size
            (((boundaryUs - pts) * sampleRate + 999_999L) / 1_000_000L)
                .coerceIn(frame + 1L, frameCount.toLong()).toInt()
        }
        val part = pcmEnergy(source, from + frame * frameBytes, (nextFrame - frame) * frameBytes, encoding)
        energy[bucket] += part.total
        counts[bucket] += part.count
        frame = nextFrame
    }
}
