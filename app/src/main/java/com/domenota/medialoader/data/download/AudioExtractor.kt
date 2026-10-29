package com.domenota.medialoader.data.download

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.domenota.medialoader.core.provider.InstagramProvider
import com.domenota.medialoader.data.storage.PublishedFile
import com.domenota.medialoader.data.storage.StorageManager
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Downloads a video, encodes its audio track as real MP3 and hands it to StorageManager.
 * The source video is downloaded again on purpose; reusing an already saved MP4 is a later optimisation.
 */
class AudioExtractor(
    private val context: Context,
    private val storage: StorageManager,
    private val instagramCookies: () -> String? = { null },
) {
    class NoAudioTrackException : Exception("В этом видео нет звуковой дорожки.")
    class VideoTooLargeException : IOException("Видео слишком большое для извлечения аудио.")
    class InsufficientStorageException : IOException("Недостаточно места для обработки аудио.")

    data class SavedAudio(val published: PublishedFile, val sizeBytes: Long)

    /**
     * Temporary recognition sample: no media is published to Downloads or the history.
     * Caller owns and must delete the returned MP3 even if recognition fails.
     */
    suspend fun recognitionSample(mediaUrl: String): File = withContext(Dispatchers.IO) {
        val source = File.createTempFile("recognition-video-", ".tmp", context.cacheDir)
        val sample = File.createTempFile("recognition-audio-", ".mp3", context.cacheDir)
        try {
            download(mediaUrl, source) { _, _ -> }
            extract(source, sample)
            sample
        } catch (error: Throwable) {
            sample.delete()
            throw error
        } finally {
            source.delete()
        }
    }

    /** @return saved file metadata and file size in bytes. */
    suspend fun saveAudio(
        videoUrl: String,
        fileName: String,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit = { _, _ -> },
        beforePublish: suspend (audioFile: File) -> Unit = {},
    ): SavedAudio = withContext(Dispatchers.IO) {
        val video = File.createTempFile("src", ".mp4", context.cacheDir)
        val audio = File.createTempFile("aud", ".mp3", context.cacheDir)
        try {
            download(videoUrl, video, onProgress)
            extract(video, audio)
            beforePublish(audio)
            SavedAudio(storage.publish(audio, fileName, "audio/mpeg"), audio.length())
        } finally {
            video.delete()
            audio.delete()
        }
    }

    private suspend fun download(
        url: String,
        target: File,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ) {
        var source = URL(url)
        repeat(5) {
            if (!InstagramProvider.safeMediaUrl(source.toString())) throw IOException("Недопустимый адрес медиа")
            val connection = source.openConnection() as HttpURLConnection
            try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12000
            connection.readTimeout = 20000
            connection.setRequestProperty("Referer", "https://www.instagram.com/")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
            if (source.host.equals("instagram.com", true) || source.host.endsWith(".instagram.com", true)) {
                instagramCookies()?.let { connection.setRequestProperty("Cookie", it) }
            }
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location") ?: throw IOException("Перенаправление без адреса")
                val next = URL(source, location)
                if (!InstagramProvider.safeMediaUrl(next.toString())) throw IOException("Недопустимый адрес медиа")
                source = next
                return@repeat
            }
            if (status !in 200..299) throw IOException("HTTP $status")
            val declared = connection.contentLengthLong.takeIf { it > 0 }
            if (declared != null && declared > MAX_VIDEO_BYTES) throw VideoTooLargeException()
            if (declared != null && declared * 2 > context.cacheDir.usableSpace) throw InsufficientStorageException()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_VIDEO_BYTES) throw VideoTooLargeException()
                        if (20L * 1024 * 1024 > context.cacheDir.usableSpace)
                            throw InsufficientStorageException()
                        output.write(buffer, 0, read)
                        onProgress(total, declared)
                    }
                }
            }
            return
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Слишком много перенаправлений")
    }

    private suspend fun extract(video: File, audio: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(video.absolutePath)
            val hasAudio = (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            if (!hasAudio) throw NoAudioTrackException()
        } finally {
            extractor.release()
        }
        // Both paths are app-controlled temp files; quoting protects the command parser from spaces.
        fun quote(file: File) = "\"${file.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        val command = "-nostdin -hide_banner -loglevel error -y -i ${quote(video)} " +
            "-map 0:a:0 -vn -c:a libmp3lame -b:a 192k -id3v2_version 3 ${quote(audio)}"
        val succeeded = suspendCancellableCoroutine<Boolean> { continuation ->
            val session = FFmpegKit.executeAsync(command) { completed ->
                if (continuation.isActive) continuation.resume(ReturnCode.isSuccess(completed.returnCode))
            }
            continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
        if (!succeeded || audio.length() == 0L) throw IOException("Не удалось преобразовать аудио в MP3.")
    }

    private companion object { const val MAX_VIDEO_BYTES = 300L * 1024 * 1024 }
}
