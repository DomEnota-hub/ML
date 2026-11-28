package com.domenota.medialoader.data.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext

/** Read-only streaming decode. No temporary audio, database, file write or content logging. */
internal object MusicLoudnessAnalyzer {
    suspend fun analyze(context: Context, uri: Uri): MusicLoudnessMeasurement? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(45_000L) {
                try { decode(context, uri) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }
        }

    private suspend fun decode(context: Context, uri: Uri): MusicLoudnessMeasurement? {
        val active = coroutineContext
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            active.ensureActive()
            extractor.setDataSource(context, uri, null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(index)
            if (format.containsKey(MediaFormat.KEY_DURATION) &&
                format.getLong(MediaFormat.KEY_DURATION) > 3_600_000_000L) return null
            extractor.selectTrack(index)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (rate !in 8000..192000 || channels !in 1..2) return null
            var meter = MusicLoudnessMeter(rate, channels)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            var emptyPolls = 0
            while (true) {
                active.ensureActive()
                if (!inputDone) {
                    val input = codec.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(input))
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val next = codec.outputFormat
                        val nextRate = next.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val nextChannels = next.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (nextRate !in 8000..192000 || nextChannels !in 1..2) return null
                        if (nextRate != rate || nextChannels != channels) {
                            if (meter.frames != 0L) return null
                            rate = nextRate; channels = nextChannels
                            meter = MusicLoudnessMeter(rate, channels)
                        }
                        encoding = if (next.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            next.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    }
                    output == MediaCodec.INFO_TRY_AGAIN_LATER -> if (++emptyPolls >= 1000) return null
                    output >= 0 -> {
                        emptyPolls = 0
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val bytes = requireNotNull(codec.getOutputBuffer(output)).order(ByteOrder.nativeOrder())
                                bytes.limit(info.offset + info.size); bytes.position(info.offset)
                                val sampleBytes = when (encoding) {
                                    AudioFormat.ENCODING_PCM_16BIT -> 2
                                    AudioFormat.ENCODING_PCM_FLOAT -> 4
                                    else -> return null
                                }
                                fun sample(): Double = if (sampleBytes == 2) bytes.short / 32768.0 else bytes.float.toDouble()
                                var n = 0
                                while (bytes.remaining() >= sampleBytes * channels) {
                                    if (n++ % 4096 == 0) active.ensureActive()
                                    val left = sample()
                                    meter.accept(left, if (channels == 2) sample() else 0.0)
                                }
                            }
                        } finally { codec.releaseOutputBuffer(output, false) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            active.ensureActive()
            return meter.finish()
        } finally {
            decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
            extractor.release()
        }
    }
}
