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
        var length = minOf(segmentMs, durationMs - start)
        // Avoid a tiny terminal piece with no decodable video frame.
        if (durationMs - (start + length) in 1L..100L) {
            length = durationMs - start
        }
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

/** Lossless, independently reversed PCM segments: one AAC encoder is used only at mux time. */
internal fun reverseAudioPcmArgs(input: File, segment: ReverseSegment, output: File): List<String> =
    listOf(
        "-hide_banner", "-nostdin", "-y",
        "-ss", "%1.3f".format(Locale.US, segment.startMs / 1000.0),
        "-t", "%1.3f".format(Locale.US, segment.durationMs / 1000.0),
        "-i", input.absolutePath, "-map", "0:a:0", "-vn",
        "-af", "aresample=48000,areverse,asetpts=PTS-STARTPTS",
        "-c:a", "pcm_s16le", "-ar", "48000", "-ac", "2", output.absolutePath,
    )

/** FFmpeg reverse filters buffer decoded frames, so only short slices are processed in memory.
 * Reverse audio to lossless PCM chunks and encode it exactly once at the final mux.
 * AAC per-fragment encoding adds encoder-delay/padding at every boundary and audibly stutters.
 */
internal class VideoReverseEngine(private val cacheDir: File) {
    suspend fun reverse(input: File, onProgress: (Int) -> Unit): File {
        val info = readMediaInfo(input)
        val segments = reverseSegments(info.durationMs, reverseChunkMillis(info.width, info.height))
        val work = File(cacheDir, "reverse-" + UUID.randomUUID()).apply { mkdirs() }
        val result = File(cacheDir, "reversed-" + UUID.randomUUID() + ".mp4")
        try {
            val created = mutableListOf<File>()
            val createdAudio = mutableListOf<File>()
            for ((index, segment) in segments.withIndex()) {
                currentCoroutineContext().ensureActive()
                val part = File(work, "part-%05d.mp4".format(Locale.ROOT, index))
                val start = "%1.3f".format(Locale.US, segment.startMs / 1000.0)
                val duration = "%1.3f".format(Locale.US, segment.durationMs / 1000.0)
                val args = mutableListOf(
                    "-hide_banner", "-nostdin", "-y", "-ss", start, "-t", duration,
                    "-i", input.absolutePath,
                    "-map", "0:v:0",
                    // Keep the source frame rate. Crop at most one pixel on odd dimensions
                    // because the MP4 encoder requires an even YUV420 image size.
                    "-vf", "crop=trunc(iw/2)*2:trunc(ih/2)*2,reverse,setpts=PTS-STARTPTS",
                    "-c:v", "mpeg4", "-q:v", "2", "-pix_fmt", "yuv420p",
                    "-video_track_timescale", "30000",
                )
                // Video chunks are silent. PCM audio is produced separately to avoid
                // gap/padding from hundreds of separately encoded AAC packets.
                args += "-an"
                args += listOf("-movflags", "+faststart", part.absolutePath)
                check(runFFmpeg(args)) { "Не удалось обработать реверс (фрагмент ${index + 1})" }
                check(part.exists() && part.length() > 0) { "Не удалось записать фрагмент реверса" }
                created += part
                if (info.hasAudio) {
                    currentCoroutineContext().ensureActive()
                    val audio = File(work, "audio-%05d.wav".format(Locale.ROOT, index))
                    check(runFFmpeg(reverseAudioPcmArgs(input, segment, audio))) {
                        "Не удалось обратить звуковую дорожку (фрагмент ${index + 1})"
                    }
                    check(audio.isFile && audio.length() > 44L) { "Не удалось записать звуковой фрагмент" }
                    createdAudio += audio
                }
                onProgress(45 + (index + 1) * 45 / segments.size)
            }
            fun manifest(files: List<File>, name: String) =
                File(work, name).also { manifest ->
                    manifest.writeText(files.joinToString("\n", postfix = "\n") { segment ->
                        "file '" + segment.absolutePath.replace("'", "'\\''") + "'"
                    })
                }
            val videoManifest = manifest(created, "video.txt")
            val audioManifest = if (createdAudio.isNotEmpty()) manifest(createdAudio, "audio.txt") else null
            val args = mutableListOf(
                "-hide_banner", "-nostdin", "-y", "-f", "concat", "-safe", "0",
                "-i", videoManifest.absolutePath,
            )
            if (audioManifest != null) args += listOf(
                "-f", "concat", "-safe", "0", "-i", audioManifest.absolutePath,
                "-map", "0:v:0", "-map", "1:a:0",
                "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest",
            ) else args += listOf("-map", "0:v:0", "-c", "copy")
            args += listOf("-movflags", "+faststart", result.absolutePath)
            val joined = runFFmpeg(args)
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
