package com.domenota.medialoader.data.recognition

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.domenota.medialoader.core.logging.AppLog
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.domenota.medialoader.core.recognition.RecognitionAudioSpec
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Decodes an already-downloaded audio file to the format expected by Shazam:
 * signed 16-bit little-endian PCM, mono, 16 kHz.
 *
 * At most the middle 12 seconds are decoded, so recognition never needs the whole MP3 in memory.
 */
class RecognitionAudioPreprocessor(private val context: Context) {
    internal suspend fun savedDurationMs(savedUri:String):Long?=withContext(Dispatchers.IO){
        val retriever=MediaMetadataRetriever()
        try{
            retriever.setDataSource(context,Uri.parse(savedUri))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf{it>0}
        }catch(_:Exception){null}finally{retriever.release()}
    }

    /** Reuse the bounded SAF/FFmpeg decoder for both saved videos and local audio. */
    suspend fun decodeVideo(savedUri: String): ShortArray {
        val duration=savedDurationMs(savedUri)
        val start=RecognitionWindowPlanner.startMs(duration,RecognitionAudioSpec.TARGET_SECONDS*1000L,0)?:0L
        return decodeSavedWindow(savedUri,start,true)
    }
    internal suspend fun decodeTrackWindow(savedUri:String,startMs:Long):ShortArray=decodeSavedWindow(savedUri,startMs,false)

    private suspend fun decodeSavedWindow(savedUri:String,startMs:Long,video:Boolean):ShortArray=withContext(Dispatchers.IO){
        require(startMs>=0)
        val uri=Uri.parse(savedUri)
        val output=File.createTempFile(if(video)"video-recognition-" else "track-recognition-", ".pcm",context.cacheDir)
        try{
            val source=when(uri.scheme){
                "content"->FFmpegKitConfig.getSafParameterForRead(context,uri)
                "file"->uri.path?:throw IOException("Файл недоступен")
                else->throw IOException("Файл недоступен")
            }
            fun quote(value:String)="\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\""
            val command="-nostdin -hide_banner -loglevel error -y "+
                "-ss ${startMs/1000.0} -i ${quote(source)} -t ${RecognitionAudioSpec.TARGET_SECONDS} "+
                "-vn -ac 1 -ar ${RecognitionAudioSpec.SAMPLE_RATE_HZ} -acodec pcm_s16le -f s16le ${quote(output.absolutePath)}"
            val succeeded=suspendCancellableCoroutine<Boolean>{continuation->
                val session=FFmpegKit.executeAsync(command){completed->
                    val success=ReturnCode.isSuccess(completed.returnCode)
                    if(!success)AppLog.w(if(video)"VideoRecognition" else "TrackRecognition","Audio window extraction failed · code=${completed.returnCode}")
                    if(continuation.isActive)continuation.resume(success)
                }
                continuation.invokeOnCancellation{FFmpegKit.cancel(session.sessionId)}
            }
            if(!succeeded||output.length()<2L||output.length()>RecognitionAudioSpec.TARGET_SAMPLES*2L+4096L)
                throw IOException(if(video)"Не удалось прочитать звук видео" else "Не удалось прочитать звук трека")
            rawPcmToShorts(output)
        }finally{output.delete()}
    }

    suspend fun decode(savedUri: String): ShortArray = withContext(Dispatchers.IO) {
        val source = File.createTempFile("recognition-source-", ".mp3", context.cacheDir)
        try {
            val uri = Uri.parse(savedUri)
            val input = when (uri.scheme) {
                "file" -> File(uri.path ?: throw IOException("Файл недоступен")).inputStream()
                "content" -> context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Файл недоступен")
                else -> throw IOException("Файл недоступен")
            }
            input.use { stream -> source.outputStream().use { stream.copyTo(it) } }
            decode(source)
        } finally {
            source.delete()
        }
    }

    suspend fun decode(input: File, attempt: Int = 0): ShortArray = withContext(Dispatchers.IO) {
        val output = File.createTempFile("recognition-", ".pcm", context.cacheDir)
        try {
            val durationMs = mediaDurationMs(input)
            val targetMs = RecognitionAudioSpec.TARGET_SECONDS * 1000L
            val startMs = RecognitionWindowPlanner.startMs(durationMs, targetMs, attempt)
                ?: if (attempt > 0) {
                    AppLog.i("Recognition", "Alternate audio window unavailable · reason=short-or-unknown-duration")
                    throw IOException("Запись слишком короткая для другого фрагмента распознавания.")
                } else 0L
            AppLog.verbose("Recognition", "Audio window · attempt=${attempt} · startMs=${startMs} · lengthMs=${targetMs} · durationMs=${durationMs ?: -1}")
            val limitMs = if (durationMs == null || durationMs > targetMs) targetMs else null
            decodeWithFfmpeg(input, output, startMs, limitMs)
            rawPcmToShorts(output)
        } finally {
            output.delete()
        }
    }

    private fun mediaDurationMs(input: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(input.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun decodeWithFfmpeg(
        input: File,
        output: File,
        startMs: Long,
        limitMs: Long?,
    ) {
        fun quote(file: File) = "\"" + file.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val seek = if (startMs > 0L) "-ss ${startMs / 1000.0} " else ""
        val limit = limitMs?.let { "-t ${it / 1000.0} " }.orEmpty()
        val command = "-nostdin -hide_banner -loglevel error -y " +
            "${seek}-i ${quote(input)} ${limit}-vn -ac 1 -ar ${RecognitionAudioSpec.SAMPLE_RATE_HZ} " +
            "-acodec pcm_s16le -f s16le ${quote(output)}"

        val succeeded = suspendCancellableCoroutine<Boolean> { continuation ->
            val session = FFmpegKit.executeAsync(command) { completed ->
                if (continuation.isActive) continuation.resume(ReturnCode.isSuccess(completed.returnCode))
            }
            continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
        if (!succeeded || !output.exists() || output.length() < 2L) {
            throw IOException("Не удалось подготовить аудио для распознавания.")
        }
    }

    private fun rawPcmToShorts(file: File): ShortArray {
        val bytes = file.readBytes()
        val usableSize = bytes.size - (bytes.size % 2)
        if (usableSize <= 0) return ShortArray(0)

        val shorts = ShortArray(usableSize / 2)
        ByteBuffer.wrap(bytes, 0, usableSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        return shorts
    }
}
