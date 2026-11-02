package com.domenota.medialoader.data.editor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Two-pass export: Media3 owns video timing / geometry, FFmpeg mixes audio into
 * that already rendered MP4 without re-encoding the video track.
 *
 * Local SAF URIs are copied only into temporary cache, never modified.
 */
internal class VideoAudioMixEngine(private val context: Context) {
    suspend fun mix(
        video: File,
        project: VideoProjectDraft,
        includeOriginalAudio: Boolean,
        onProgress: (Int) -> Unit,
    ): File {
        val tracks = project.visibleAudioTracks()
        if (tracks.isEmpty()) return video
        val work = File(context.cacheDir, "video-audio-stage4-${UUID.randomUUID()}")
        val output = File(context.cacheDir, "video-audio-mix-${UUID.randomUUID()}.mp4")
        require(work.mkdirs()) { "Не удалось создать временную папку аудиомонтажа" }
        try {
            val audioInputs = withContext(Dispatchers.IO) {
                tracks.mapIndexed { index, track ->
                    currentCoroutineContext().ensureActive()
                    val copy = File(work, "track-${index}.audio")
                    val source = context.contentResolver.openInputStream(Uri.parse(track.sourceUri))
                        ?: error("Недоступен источник аудиодорожки ${index + 1}")
                    source.use { input -> copy.outputStream().use { input.copyTo(it) } }
                    require(copy.length() > 0L) { "Пустая аудиодорожка ${index + 1}" }
                    copy
                }
            }
            val hasOriginalAudio = includeOriginalAudio && withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(video.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                        .equals("yes", ignoreCase = true)
                } finally { retriever.release() }
            }
            onProgress(10)
            val args = VideoAudioMixCommand.build(video, audioInputs, tracks,
                output, hasOriginalAudio, project.durationMs)
            val ok = suspendCancellableCoroutine<Boolean> { continuation ->
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                var reported = 10
                val session = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray(), { completed ->
                    if (continuation.isActive) continuation.resume(ReturnCode.isSuccess(completed.returnCode))
                }, null, { statistics ->
                    // FFmpeg time is encoded output milliseconds, after trim/atempo/fade.
                    if (statistics.time.isFinite() && statistics.time >= 0) {
                        val percent = (10 + statistics.time * 89 / project.durationMs).toInt().coerceIn(10, 99)
                        mainHandler.post {
                            if (continuation.isActive && percent > reported) {
                                reported = percent
                                onProgress(percent)
                            }
                        }
                    }
                })
                continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
            }
            check(ok && output.isFile && output.length() > 0L) {
                "Не удалось свести аудиодорожки в итоговое видео"
            }
            onProgress(100)
            return output
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            work.deleteRecursively()
        }
    }
}

/** Pure FFmpeg argument composer: independently editable overlays, no source mutation. */
internal object VideoAudioMixCommand {
    private fun seconds(ms: Long): String = "%.3f".format(Locale.US, ms / 1000.0)

    fun build(
        video: File,
        audioInputs: List<File>,
        tracks: List<VideoAudioTrackDraft>,
        output: File,
        includeOriginalAudio: Boolean,
        projectDurationMs: Long,
    ): List<String> {
        require(audioInputs.isNotEmpty() && audioInputs.size == tracks.size)
        require(projectDurationMs > 0)
        require(tracks.all { it.atMs < projectDurationMs &&
            it.durationMs <= projectDurationMs - it.atMs })
        val args = mutableListOf("-hide_banner", "-nostdin", "-y",
            "-i", video.absolutePath)
        audioInputs.forEach { args += listOf("-i", it.absolutePath) }
        val filters = mutableListOf<String>()
        val mixes = mutableListOf<String>()
        if (includeOriginalAudio) {
            filters += "[0:a:0]aresample=48000,aformat=channel_layouts=stereo," +
                "asetpts=PTS-STARTPTS[a0]"
            mixes += "[a0]"
        }
        tracks.forEachIndexed { index, track ->
            val label = "a${index + 1}"
            val commands = mutableListOf(
                "atrim=start=${seconds(track.inMs)}:end=${seconds(track.outMs)}",
                "asetpts=PTS-STARTPTS",
                "aresample=48000",
                "aformat=channel_layouts=stereo",
                "volume=${"%.3f".format(Locale.US, track.volume)}",
            )
            // atempo preserves pitch and must precede fades, whose times are OUTPUT time.
            var tempo = track.speed
            val tempoFilters = mutableListOf<String>()
            while (tempo < .5f) { tempoFilters += "atempo=0.5"; tempo /= .5f }
            while (tempo > 2f) { tempoFilters += "atempo=2.0"; tempo /= 2f }
            if (tempo != 1f) tempoFilters += "atempo=${"%.6f".format(Locale.US, tempo)}"
            commands.addAll(2, tempoFilters)
            if (track.fadeInMs > 0L)
                commands += "afade=t=in:st=0:d=${seconds(track.fadeInMs)}"
            if (track.fadeOutMs > 0L) {
                val start = (track.durationMs - track.fadeOutMs).coerceAtLeast(0L)
                commands += "afade=t=out:st=${seconds(start)}:d=${seconds(track.fadeOutMs)}"
            }
            if (track.atMs > 0L) commands += "adelay=${track.atMs}:all=1"
            filters += "[${index + 1}:a:0]${commands.joinToString(",")}[${label}]"
            mixes += "[${label}]"
        }
        filters += if (mixes.size == 1) {
            "${mixes.single()}anull[aout]"
        } else {
            "${mixes.joinToString("")}amix=inputs=${mixes.size}:" +
                "duration=longest:dropout_transition=0:normalize=0[aout]"
        }
        args += listOf("-filter_complex", filters.joinToString(";"),
            "-map", "0:v:0", "-map", "[aout]",
            "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
            "-ar", "48000", "-ac", "2",
            "-t", seconds(projectDurationMs),
            "-movflags", "+faststart", output.absolutePath)
        return args
    }
}
