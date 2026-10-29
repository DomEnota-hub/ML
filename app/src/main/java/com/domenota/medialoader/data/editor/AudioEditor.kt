package com.domenota.medialoader.data.editor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class AudioEditSpec(
    val startMs: Long = 0L,
    val endMs: Long? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val replacementCoverUri: String? = null,
    val removeCover: Boolean = false,
) {
    init {
        require(startMs >= 0L)
        require(endMs == null || endMs > startMs)
    }
}

class AudioEditor(private val context: Context) {
    /** Converts the temporary AAC stream produced by Media3 into the app's standard MP3 output. */
    suspend fun convertExtractedAudioToMp3(
        source: File,
        onProgress: (Int) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        require(source.exists() && source.length() > 0L) { "Временный аудиофайл пуст." }
        val output = File(context.cacheDir, "audio-extract-${UUID.randomUUID()}.mp3")
        try {
            onProgress(5)
            val command = "-nostdin -hide_banner -loglevel error -y -i " + quote(source.absolutePath) +
                " -map 0:a:0 -c:a libmp3lame -b:a 192k -id3v2_version 3 " + quote(output.absolutePath)
            val succeeded = suspendCancellableCoroutine<Boolean> { continuation ->
                val session = FFmpegKit.executeAsync(command) { completed ->
                    if (continuation.isActive) continuation.resume(ReturnCode.isSuccess(completed.returnCode))
                }
                continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
            }
            if (!succeeded || !output.exists() || output.length() == 0L) {
                throw IOException("Не удалось конвертировать аудио в MP3.")
            }
            onProgress(100)
            output
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    suspend fun export(
        source: Uri,
        spec: AudioEditSpec,
        onProgress: (Int) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val sourceFile = File(context.cacheDir, "audio-edit-source-${UUID.randomUUID()}.tmp")
        val coverFile = File(context.cacheDir, "audio-edit-cover-${UUID.randomUUID()}.img")
        val output = File(context.cacheDir, "audio-edit-${UUID.randomUUID()}.mp3")
        try {
            onProgress(5)
            copyUri(source, sourceFile)
            onProgress(15)
            val hasCover = when {
                spec.removeCover -> false
                spec.replacementCoverUri != null -> {
                    copyUri(Uri.parse(spec.replacementCoverUri), coverFile, MAX_COVER_BYTES)
                    coverFile.length() > 0L
                }
                else -> extractEmbeddedCover(source, coverFile)
            }
            onProgress(25)
            val command = buildCommand(sourceFile, coverFile.takeIf { hasCover }, output, spec)
            val succeeded = suspendCancellableCoroutine<Boolean> { continuation ->
                val session = FFmpegKit.executeAsync(command) { completed ->
                    if (continuation.isActive) {
                        continuation.resume(ReturnCode.isSuccess(completed.returnCode))
                    }
                }
                continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
            }
            if (!succeeded || !output.exists() || output.length() == 0L) {
                throw IOException("Не удалось сохранить аудио.")
            }
            onProgress(100)
            output
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            sourceFile.delete()
            coverFile.delete()
        }
    }

    private suspend fun copyUri(uri: Uri, target: File, maxBytes: Long? = null) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (maxBytes != null && total > maxBytes) {
                        throw IOException("Обложка слишком большая.")
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IOException("Не удалось открыть исходный файл.")
        if (target.length() == 0L) throw IOException("Файл пуст.")
    }

    private fun extractEmbeddedCover(source: Uri, target: File): Boolean =
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, source)
                val bytes = retriever.embeddedPicture ?: return@runCatching false
                if (bytes.size > MAX_COVER_BYTES) return@runCatching false
                target.writeBytes(bytes)
                target.length() > 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(false)

    private fun buildCommand(
        source: File,
        cover: File?,
        output: File,
        spec: AudioEditSpec,
    ): String {
        val durationMs = spec.endMs?.minus(spec.startMs)
        return buildString {
            append("-nostdin -hide_banner -loglevel error -y ")
            if (spec.startMs > 0L) {
                append("-ss ").append(formatSeconds(spec.startMs)).append(' ')
            }
            append("-i ").append(quote(source.absolutePath)).append(' ')
            if (cover != null) append("-i ").append(quote(cover.absolutePath)).append(' ')
            durationMs?.takeIf { it > 0L }?.let {
                append("-t ").append(formatSeconds(it)).append(' ')
            }
            append("-map 0:a:0 ")
            if (cover != null) append("-map 1:v:0 ")
            append("-c:a libmp3lame -b:a 192k ")
            if (cover != null) {
                append("-c:v mjpeg -disposition:v:0 attached_pic ")
                append("-metadata:s:v title=").append(quote("Album cover")).append(' ')
                append("-metadata:s:v comment=").append(quote("Cover (front)")).append(' ')
            }
            append("-id3v2_version 3 -write_id3v1 1 ")
            append("-metadata title=").append(quote(spec.title.trim())).append(' ')
            append("-metadata artist=").append(quote(spec.artist.trim())).append(' ')
            append("-metadata album=").append(quote(spec.album.trim())).append(' ')
            append(quote(output.absolutePath))
        }
    }

    private fun formatSeconds(ms: Long): String =
        String.format(java.util.Locale.US, "%.3f", ms / 1000.0)

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val MAX_COVER_BYTES = 10L * 1024L * 1024L
    }
}
