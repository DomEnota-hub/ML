package com.domenota.medialoader.data.playback.alignment

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.domenota.medialoader.data.library.AcousticWindow
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.nio.ByteOrder

/** Bounded in-memory PCM, never written to disk. Platform resampling needs device QA. */
internal object LyricsAudioDecoder {
    class Pcm {
        private val blocks=ArrayList<FloatArray>()
        var size=0;private set
        fun add(value:Float) {
            require(value.isFinite() && size<16000*600){"Композиция слишком длинная или PCM повреждён"}
            if(size%320000==0)blocks.add(FloatArray(320000))
            blocks[size/320000][size%320000]=value;size++
        }
        fun slice(start:Int,end:Int)=FloatArray(end-start){index->val p=start+index;blocks[p/320000][p%320000]}
    }
    suspend fun decode(context:Context,uri:Uri,check:()->Unit):Pcm {
        val pcm=Pcm()
        val ok=decodeWindow(context,uri,AcousticWindow(0,null),{require(it in 0..40){"Неподдерживаемое начало аудиопотока"}}){sample->
            if(pcm.size%4096==0)check();pcm.add(sample)
        }
        check();require(ok && pcm.size>=320){"Не удалось декодировать аудио"};return pcm
    }
    private suspend fun decodeWindow(context:Context,uri:Uri,window:AcousticWindow,beginWindow:(Long)->Unit,accept:(Float)->Unit):Boolean {
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
            require(rate in 16000..384000 && channels in 1..16)
            val timeline=PcmTimeline()
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
                        val nextRate=output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val nextChannels=output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val nextEncoding=if(output.containsKey(MediaFormat.KEY_PCM_ENCODING))output.getInteger(MediaFormat.KEY_PCM_ENCODING)else AudioFormat.ENCODING_PCM_16BIT
                        require(!started || (nextRate==rate && nextChannels==channels && nextEncoding==encoding)){"Формат PCM изменился"}
                        rate=nextRate;channels=nextChannels;encoding=nextEncoding
                        require(rate in 16000..384000 && channels in 1..16)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> { check(++emptyPolls < 1000) { "Audio decoder stalled" } }
                    else -> if (index >= 0) {
                        emptyPolls = 0
                        try {
                            val buffer = requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset+info.size)
                            val sampleBytes = when (encoding) { AudioFormat.ENCODING_PCM_16BIT -> 2; AudioFormat.ENCODING_PCM_FLOAT -> 4; else -> error("Unsupported PCM encoding") }
                            val frameBytes=sampleBytes*channels
                            val frames=buffer.remaining()/frameBytes
                            require(info.presentationTimeUs in -1000000L..601000000L)
                            val skip=((startUs-info.presentationTimeUs).coerceAtLeast(0)*rate/1_000_000L).coerceAtMost(frames.toLong()).toInt()
                            val until=if(endUs==null)frames else ((endUs-info.presentationTimeUs).coerceAtLeast(0)*rate/1_000_000L).coerceAtMost(frames.toLong()).toInt()
                            timeline.accept(info.presentationTimeUs+skip*1_000_000L/rate,(until-skip).coerceAtLeast(0),rate)
                            buffer.position(buffer.position()+skip*frameBytes)
                            if(!started&&until>skip){beginWindow((info.presentationTimeUs+skip*1_000_000L/rate)/1000);started=true}
                            repeat((until-skip).coerceAtLeast(0)) {
                                var mono = 0.0
                                repeat(channels) { mono += if (sampleBytes == 2) buffer.short/32768.0 else buffer.float.toDouble() }
                                sum += mono/channels; count++; phase += 16000
                                if (phase >= rate) { accept((sum/count).toFloat());accepted++; phase -= rate; sum = 0.0; count = 0 }
                            }
                            if(endUs!=null&&until<frames)windowDone=true
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
