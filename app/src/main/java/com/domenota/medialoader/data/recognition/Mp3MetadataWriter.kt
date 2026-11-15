package com.domenota.medialoader.data.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class Mp3MetadataWriter(private val context: Context) {
    internal data class Tagged(val file:File,val newArtworkApplied:Boolean)
    suspend fun write(input:File,result:MusicRecognitionResult):File=writeInternal(input,result,false).file
    internal suspend fun writePreserving(input:File,result:MusicRecognitionResult):Tagged=writeInternal(input,result,true)

    private suspend fun writeInternal(input:File,result:MusicRecognitionResult,preserveExistingArtwork:Boolean):Tagged = withContext(Dispatchers.IO) {
        val output = File.createTempFile("recognized-", ".mp3", context.cacheDir)
        var artwork: File? = null
        try {
            requireActualMp3(input)
            artwork = result.artworkUrl?.let { url -> downloadArtwork(url)?.let { downloaded ->
                try { normalizedArtwork(downloaded) } finally { downloaded.delete() }
            } }
            // Prefer metadata-only editing: no muxer, no JPEG encoder in the
            // audio-tier FFmpeg, no changes to compressed audio or gapless frames.
            val jobContext = coroutineContext
            try {
                DirectMp3Tags.write(input, output, result, artwork, preserveExistingArtwork) { jobContext.ensureActive() }
                check(verifiedTags(output, result, artwork != null)) { "Written tags could not be verified" }
                AppLog.i("Recognition", "MP3 metadata saved · direct ID3 · newArtwork=${artwork != null}")
                return@withContext Tagged(output, artwork != null)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                output.delete()
                AppLog.w("Recognition", "Direct ID3 unavailable; trying MP3 remux")
            }
            // Keep optional artwork if supported. Copy a normalized JPEG instead
            // of requiring the video encoder in the audio-only FFmpeg build.
            val firstPass = executeCommand(buildArguments(input, output, result, artwork, preserveExistingArtwork))
            if (firstPass && verifiedTags(output, result, artwork != null)) {
                Tagged(output, artwork != null)
            } else if (artwork != null || preserveExistingArtwork) {
                AppLog.w("Recognition", "MP3 cover/tag pass failed; retrying audio-only tags")
                output.delete()
                val tagsOnly = executeCommand(buildArguments(input, output, result, null, false))
                if (!tagsOnly || !verifiedTags(output, result, false))
                    throw IOException("Не удалось записать MP3-теги даже без обложки.")
                AppLog.i("Recognition", "MP3 metadata saved without artwork")
                Tagged(output, false)
            } else throw IOException("Не удалось записать метаданные MP3 без обложки.")
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally { artwork?.delete() }
    }

    private fun verifiedTags(file: File, result: MusicRecognitionResult, newArtwork: Boolean): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(file.absolutePath)
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) == result.title &&
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) == result.artist &&
                (result.album.isNullOrBlank() || reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) == result.album) &&
                (!newArtwork || (reader.embeddedPicture?.isNotEmpty() == true))
        } catch (_: Exception) { false }
        finally { reader.release() }
    }

    private fun requireActualMp3(input: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            val audio = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                .filter { it?.startsWith("audio/") == true }
            if (audio.size != 1 || audio.single() != "audio/mpeg") {
                AppLog.w("Recognition", "MP3 tag write rejected · source is not a single MPEG audio stream")
                throw IOException("Фактический формат файла не MP3. Оригинал не изменён.")
            }
        } finally { extractor.release() }
    }

    private fun normalizedArtwork(input: File): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth,bounds.outHeight).toLong()/sample > 1024) sample *= 2
        val bitmap = BitmapFactory.decodeFile(input.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        var target: File? = null
        return try {
            val jpeg = File.createTempFile("cover-normalized-", ".jpg", context.cacheDir).also { target = it }
            check(jpeg.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) })
            jpeg
        } catch (_: Exception) { target?.delete(); null }
        finally { bitmap.recycle() }
    }

    private suspend fun executeCommand(args: List<String>): Boolean =
        suspendCancellableCoroutine { continuation ->
            val session = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray()) { completed ->
                if (continuation.isActive) {
                    val ok = ReturnCode.isSuccess(completed.returnCode)
                    if (!ok) {
                        // Raw FFmpeg output can contain filenames, paths and tags.
                        // Keep the return code and a bounded, allowlisted cause instead.
                        val diagnostic = Mp3TagArguments.failureKind(completed.output.orEmpty())
                        AppLog.w("Recognition", "MP3 remux failed · code=${completed.returnCode} · cause=$diagnostic")
                    }
                    continuation.resume(ok)
                }
            }
            continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }

    private fun buildArguments(input: File, output: File, result: MusicRecognitionResult,
        artwork: File?, preserveExistingArtwork: Boolean): List<String> =
        Mp3TagArguments.build(input.absolutePath, output.absolutePath, result, artwork?.absolutePath, preserveExistingArtwork)

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
