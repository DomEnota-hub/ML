package com.domenota.medialoader.data.editor

import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal data class ReverseSegment(val startMs: Long, val durationMs: Long)

/** Segments are returned in output order (last source fragment first). */
internal fun reverseSegments(durationMs: Long, segmentMs: Long): List<ReverseSegment> {
    require(durationMs > 0L && segmentMs > 0L)
    val segments = mutableListOf<ReverseSegment>()
    var start = 0L
    while (start < durationMs) {
        val length = minOf(segmentMs, durationMs - start)
        segments += ReverseSegment(start, length)
        start += length
    }
    return segments.asReversed()
}

internal fun reverseChunkMillis(width: Int, height: Int): Long {
    val pixels = width.toLong() * height
    return when {
        pixels > 1920L * 1080L -> 500L
        pixels > 1280L * 720L -> 1_000L
        pixels > 640L * 480L -> 2_000L
        else -> 3_000L
    }
}

/** FFmpeg reverse filters buffer decoded frames, so only short slices are processed in memory. */
internal class VideoReverseEngine(private val cacheDir: File) {
    suspend fun reverse(input: File, onProgress: (Int) -> Unit): File {
        val info = readMediaInfo(input)
        val segments = reverseSegments(info.durationMs, reverseChunkMillis(info.width, info.height))
        val work = File(cacheDir, "reverse-" + UUID.randomUUID()).apply { mkdirs() }
        val result = File(cacheDir, "reversed-" + UUID.randomUUID() + ".mp4")
        try {
            val created = mutableListOf<File>()
            for ((index, segment) in segments.withIndex()) {
                currentCoroutineContext().ensureActive()
                val part = File(work, "part-%05d.mp4".format(Locale.ROOT, index))
                val start = "%1.3f".format(Locale.US, segment.startMs / 1000.0)
                val duration = "%1.3f".format(Locale.US, segment.durationMs / 1000.0)
                val args = mutableListOf(
                    "-hide_banner", "-nostdin", "-y", "-ss", start, "-t", duration,
                    "-i", input.absolutePath,
                    "-map", "0:v:0",
                    "-vf", "reverse,setpts=PTS-STARTPTS",
                    "-c:v", "mpeg4", "-q:v", "2", "-pix_fmt", "yuv420p",
                    "-r", "30", "-video_track_timescale", "30000",
                )
                if (info.hasAudio) {
                    args += listOf("-map", "0:a:0", "-af", "areverse,asetpts=PTS-STARTPTS",
                        "-c:a", "aac", "-b:a", "192k")
                } else args += "-an"
                args += listOf("-movflags", "+faststart", part.absolutePath)
                check(runFFmpeg(args)) { "Не удалось обработать реверс (фрагмент ${index + 1})" }
                check(part.exists() && part.length() > 0) { "Не удалось записать фрагмент реверса" }
                created += part
                onProgress(45 + (index + 1) * 47 / segments.size)
            }
            val manifest = File(work, "segments.txt")
            manifest.writeText(created.joinToString("\n", postfix = "\n") { segment ->
                "file '" + segment.absolutePath.replace("'", "'\\''") + "'"
            })
            val joined = runFFmpeg(listOf(
                "-hide_banner", "-nostdin", "-y", "-f", "concat", "-safe", "0",
                "-i", manifest.absolutePath,
                "-c", "copy", "-movflags", "+faststart", result.absolutePath,
            ))
            check(joined && result.exists() && result.length() > 0) {
                "Не удалось объединить фрагменты обратного видео"
            }
            onProgress(100)
            return result
        } catch (error: Throwable) {
            result.delete()
            throw error
        } finally {
            work.deleteRecursively()
        }
    }

    private suspend fun runFFmpeg(args: List<String>): Boolean =
        suspendCancellableCoroutine { continuation ->
            val session = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray()) { completed ->
                if (continuation.isActive) continuation.resume(ReturnCode.isSuccess(completed.returnCode))
            }
            continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }

    private data class MediaInfo(val durationMs: Long, val width: Int, val height: Int, val hasAudio: Boolean)

    private fun readMediaInfo(file: File): MediaInfo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            require(duration > 0) { "Не удалось определить длительность видео для реверса" }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 1920
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 1080
            val audio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                .equals("yes", ignoreCase = true)
            return MediaInfo(duration, width, height, audio)
        } finally {
            retriever.release()
        }
    }
}
