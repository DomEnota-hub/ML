package com.domenota.medialoader.data.library

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.nio.ByteOrder

/** Streaming platform decoder. One codec/FFT worker, bounded buffers, no temporary PCM files. */
internal object AcousticAudioDecoder {
    suspend fun fingerprint(context:Context,uri:Uri,durationMs:Long?=null,expanded:Boolean=false):AcousticFingerprint? {
        val active=coroutineContext
        val builder=AcousticFingerprints.Builder{active.ensureActive()}
        for(window in AcousticScanPlan.windows(durationMs,expanded)){
            active.ensureActive()
            if(!decodeWindow(context,uri,window,builder))return null
        }
        return builder.finish()
    }
    private suspend fun decodeWindow(context:Context,uri:Uri,window:AcousticWindow,builder:AcousticFingerprints.Builder):Boolean {
        val active = coroutineContext
        val previousPriority = android.os.Process.getThreadPriority(android.os.Process.myTid())
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context,uri,null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            if(window.startMs>0)extractor.seekTo(window.startMs*1000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            decoder = codec
            codec.configure(format,null,null,0); codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputDone = false
            var emptyPolls = 0
            val info = MediaCodec.BufferInfo()
            var started=false;var accepted=0L;var windowDone=false
            val startUs=window.startMs*1000;val endUs=window.endMs?.times(1000)
            // Box-filter resampling avoids selecting a single aliased sample and keeps constant memory.
            var phase = 0L; var sum = 0.0; var count = 0
            while (!windowDone) {
                active.ensureActive()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index)); buffer.clear()
                        val size = extractor.readSampleData(buffer,0)
                        if (size < 0) { codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(index,0,size,extractor.sampleTime,0); extractor.advance() }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info,10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(rate >= AcousticFingerprints.RATE && channels in 1..16)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> { check(++emptyPolls < 1000) { "Audio decoder stalled" } }
                    else -> if (index >= 0) {
                        emptyPolls = 0
                        try {
                            val buffer = requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset+info.size)
                            val sampleBytes = when (encoding) { AudioFormat.ENCODING_PCM_16BIT -> 2; AudioFormat.ENCODING_PCM_FLOAT -> 4; else -> error("Unsupported PCM encoding") }
                            var sampleIndex=0L
                            while (buffer.remaining() >= sampleBytes*channels) {
                                val pts=info.presentationTimeUs+sampleIndex++*1_000_000L/rate
                                if(endUs!=null&&pts>=endUs){windowDone=true;break}
                                var mono = 0.0
                                repeat(channels) { mono += if (sampleBytes == 2) buffer.short/32768.0 else buffer.float.toDouble() }
                                if(pts<startUs)continue
                                if(!started){builder.beginWindow(pts/1000);started=true}
                                sum += mono/channels; count++; phase += AcousticFingerprints.RATE
                                if (phase >= rate) { builder.accept((sum/count).toFloat());accepted++; phase -= rate; sum = 0.0; count = 0 }
                            }
                        } finally { codec.releaseOutputBuffer(index,false) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return accepted>0
        } finally {
            decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }; extractor.release()
            android.os.Process.setThreadPriority(previousPriority)
        }
    }
}
