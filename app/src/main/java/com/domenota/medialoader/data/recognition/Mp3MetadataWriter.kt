package com.domenota.medialoader.data.recognition

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class Mp3MetadataWriter(private val context: Context) {
    suspend fun write(
        input: File,
        result: MusicRecognitionResult,
    ): File = withContext(Dispatchers.IO) {
        val output = File.createTempFile("recognized-", ".mp3", context.cacheDir)
        val artwork = result.artworkUrl?.let { downloadArtwork(it) }
        try {
            // Tagging must not fail completely just because an optional remote cover
            // cannot be embedded. Keep the downloaded MP3 untouched until a valid
            // replacement has been produced.
            val firstPass = executeCommand(buildCommand(input, output, result, artwork))
            if (firstPass && output.isFile && output.length() > 0L) {
                output
            } else if (artwork != null) {
                AppLog.w("Recognition", "MP3 cover/tag pass failed; retrying tags without artwork")
                output.delete()
                val tagsOnly = executeCommand(buildCommand(input, output, result, artwork = null))
                if (!tagsOnly || !output.isFile || output.length() == 0L) {
                    throw IOException("Не удалось записать MP3-теги даже без обложки.")
                }
                AppLog.i("Recognition", "MP3 metadata saved without optional cover")
                output
            } else {
                throw IOException("Не удалось записать метаданные MP3 без обложки.")
            }
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            artwork?.delete()
        }
    }

    private suspend fun executeCommand(command: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val session = FFmpegKit.executeAsync(command) { completed ->
                if (continuation.isActive) {
                    continuation.resume(ReturnCode.isSuccess(completed.returnCode))
                }
            }
            continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }

    private fun buildCommand(
        input: File,
        output: File,
        result: MusicRecognitionResult,
        artwork: File?,
    ): String {
        val inputs = buildString {
            append("-i ").append(quote(input.absolutePath)).append(' ')
            if (artwork != null) append("-i ").append(quote(artwork.absolutePath)).append(' ')
        }
        val maps = if (artwork != null) {
            "-map 0:a:0 -map 1:v:0 -c:a copy -c:v mjpeg " +
                "-metadata:s:v title=${quote("Album cover")} " +
                "-metadata:s:v comment=${quote("Cover (front)")} "
        } else {
            "-map 0:a:0 -c:a copy "
        }

        return buildString {
            append("-nostdin -hide_banner -loglevel error -y ")
            append(inputs)
            append(maps)
            append("-id3v2_version 3 -write_id3v1 1 ")
            append("-metadata title=").append(quote(result.title)).append(' ')
            append("-metadata artist=").append(quote(result.artist)).append(' ')
            result.album?.takeIf { it.isNotBlank() }?.let {
                append("-metadata album=").append(quote(it)).append(' ')
            }
            append(quote(output.absolutePath))
        }
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun downloadArtwork(rawUrl: String): File? {
        val url = runCatching { URL(rawUrl) }.getOrNull() ?: return null
        if (!url.protocol.equals("https", ignoreCase = true)) return null

        val target = File.createTempFile("cover-", ".img", context.cacheDir)
        return try {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "MediaLoader/1.4.8")
                if (connection.responseCode !in 200..299) {
                    target.delete()
                    return null
                }
                val length = connection.contentLengthLong
                if (length > MAX_ARTWORK_BYTES) {
                    target.delete()
                    return null
                }
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_ARTWORK_BYTES) {
                                target.delete()
                                return null
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                target.takeIf { it.length() > 0L }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            target.delete()
            null
        }
    }

    private companion object {
        const val MAX_ARTWORK_BYTES = 10L * 1024 * 1024
    }
}
