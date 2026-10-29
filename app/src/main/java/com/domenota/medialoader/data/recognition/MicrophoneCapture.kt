package com.domenota.medialoader.data.recognition

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.domenota.medialoader.core.recognition.RecognitionAudioSpec
import java.io.IOException
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Collects a fixed PCM window, including devices that return short microphone reads. */
internal suspend fun collectMicrophonePcm(
    targetSamples: Int,
    sampleRate: Int,
    read: (ShortArray, Int, Int) -> Int,
    onProgress: suspend (Int) -> Unit,
    minSamples: Int = targetSamples,
    shouldFinish: () -> Boolean = { false },
    onLevel: suspend (Float) -> Unit = {},
): ShortArray {
    require(targetSamples > 0 && sampleRate > 0 && minSamples in 1..targetSamples)
    val pcm = ShortArray(targetSamples)
    var filled = 0
    var reportedSeconds = 0
    while (filled < pcm.size) {
        currentCoroutineContext().ensureActive()
        if (filled >= minSamples && shouldFinish()) break
        val requested = minOf(1600, pcm.size - filled)
        val count = read(pcm, filled, requested)
        if (count <= 0 || count > requested) throw IOException("Не удалось записать звук с микрофона")
        var energy = 0.0
        for (index in filled until filled + count) {
            val sample = pcm[index].toDouble()
            energy += sample * sample
        }
        // Speech recorded by phone microphones is often only a few hundred PCM units RMS.
        // A perceptual curve makes that range visible while keeping the noise floor still.
        val rms = sqrt(energy / count)
        onLevel(sqrt(((rms - 32.0) / 3_000.0).coerceIn(0.0, 1.0)).toFloat())
        filled += count
        val seconds = filled / sampleRate
        if (seconds > reportedSeconds) {
            reportedSeconds = seconds
            onProgress(seconds)
        }
    }
    return if (filled == pcm.size) pcm else pcm.copyOf(filled)
}

internal class MicrophoneCapture {
    @SuppressLint("MissingPermission") // UI requests RECORD_AUDIO before calling capture.
    suspend fun capture(onProgress: suspend (Int) -> Unit,
        shouldFinish: () -> Boolean = { false }, onLevel: suspend (Float) -> Unit = {}): ShortArray =
        withContext(Dispatchers.IO) {
        val sampleRate = RecognitionAudioSpec.SAMPLE_RATE_HZ
        val minBytes = AudioRecord.getMinBufferSize(sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) throw IOException("Микрофон не поддерживает запись для распознавания")
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes, 8192))
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED)
                throw IOException("Не удалось запустить микрофон")
            recorder.startRecording()
            collectMicrophonePcm(RecognitionAudioSpec.TARGET_SAMPLES, sampleRate,
                read = { buffer, offset, count ->
                    recorder.read(buffer, offset, count, AudioRecord.READ_BLOCKING)
                }, onProgress = onProgress, minSamples = RecognitionAudioSpec.MIN_SAMPLES,
                shouldFinish = shouldFinish, onLevel = onLevel)
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                runCatching { recorder.stop() }
            recorder.release()
        }
    }
}
