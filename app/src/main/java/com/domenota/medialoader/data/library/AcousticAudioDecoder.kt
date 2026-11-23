package com.domenota.medialoader.data.library

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.domenota.medialoader.core.logging.AppLog
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.nio.ByteOrder

/** Streaming platform decoder. One codec/FFT worker, bounded buffers, no temporary PCM files. */
internal object AcousticAudioDecoder {
    suspend fun fingerprint(context:Context,uri:Uri,durationMs:Long?=null,expanded:Boolean=false):AcousticFingerprint? {
        val active=coroutineContext
        suspend fun decode(windows:List<AcousticWindow>):AcousticFingerprint?{
            val builder=AcousticFingerprints.Builder{active.ensureActive()}
            for(window in windows){active.ensureActive();if(!decodeWindow(context,uri,window,builder::beginWindow,builder::accept))return null}
            return builder.finish()
        }
        val windows=AcousticScanPlan.windows(durationMs,expanded)
        val first=try{decode(windows)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){if(windows.size==1)throw e else null}
        // Some providers/codecs cannot seek reliably; do not silently omit their acoustic analysis.
        return first ?: if(windows.size>1)decode(listOf(AcousticWindow(0,null))) else null
    }
    suspend fun comparisonFrames(context:Context,uri:Uri,stepMs:Long):List<ComparisonFrame>? {
        require(stepMs in setOf(256L,512L,1024L))
        val active = coroutineContext
        val builder = AcousticFingerprints.ComparisonBuilder(stepMs) { active.ensureActive() }
        if (!decodeWindow(context,uri,AcousticWindow(0,null),{},builder::accept)) return null
        active.ensureActive()
        return builder.finish()
    }
    /** Confirm only tiny discarded coarse candidates; do not decode either full
     * song again. Guarded URI stamps and privacy are checked by the caller both
     * before and after the bounded additional reads.
     */
    suspend fun refineShortDifferences(
        context: Context, firstUri: Uri, secondUri: Uri,
        aligned: AlignedAudioComparison,
    ): AlignedAudioComparison {
        if (aligned.stepMs != AudioPairAlignment.BASE_STEP_MS || aligned.shortCandidates.isEmpty())
            return aligned
        val job = coroutineContext
        val accepted = mutableSetOf<ComparisonZone>()
        var spectralAccepted = 0
        var pcmAccepted = 0
        var decodeUnavailable = 0
        // Keep candidate count and PCM memory bounded; never re-decode whole songs.
        val candidates = aligned.shortCandidates.take(48)
        for (candidate in candidates) {
            job.ensureActive()
            val column = aligned.columns[candidate.start]
            val firstIndex = column.first ?: continue
            val secondIndex = column.second ?: continue
            val leftBaseMs = firstIndex * aligned.stepMs
            val rightBaseMs = secondIndex * aligned.stepMs
            val leftStart = (leftBaseMs - 128L).coerceAtLeast(0L)
            val rightStart = (rightBaseMs - 128L).coerceAtLeast(0L)
            val left = shortComparisonFrames(context, firstUri, leftStart, leftBaseMs + aligned.stepMs + 128L)
            val right = shortComparisonFrames(context, secondUri, rightStart, rightBaseMs + aligned.stepMs + 128L)
            if (left == null || right == null) {
                decodeUnavailable++
                continue
            }
            val coreLeft = ((leftBaseMs - leftStart) / 64L).toInt()
            val coreRight = ((rightBaseMs - rightStart) / 64L).toInt()
            val spectrumAccepted = AudioPairAlignment.confirmsShortDifference(
                left.frames, right.frames, coreLeft, coreRight, 4)
            if (spectrumAccepted) {
                spectralAccepted++
                accepted.add(candidate)
            } else if (coreLeft == coreRight &&
                AudioPcmShortDifference.confirms(left.samples, right.samples,
                    contextFrames = coreLeft, coreFrames = 4)) {
                // Soundtrack spectra may remain similar despite a removed word.
                // Local 8-kHz PCM residuals are aligned on unchanged context.
                pcmAccepted++
                accepted.add(candidate)
            }
        }
        // No titles, names, file paths, URI, or PCM ever enter diagnostics.
        AppLog.i("MusicCompare",
            "shortRefine candidates=${aligned.shortCandidates.size} inspected=${candidates.size} " +
                "spectral=$spectralAccepted pcm=$pcmAccepted unavailable=$decodeUnavailable")
        return aligned.withConfirmedShortZones(accepted)
    }

    private data class ShortWindow(val frames: List<ComparisonFrame>, val samples: FloatArray)

    private suspend fun shortComparisonFrames(
        context: Context, uri: Uri, startMs: Long, endMs: Long,
    ): ShortWindow? {
        val job = coroutineContext
        val builder = AcousticFingerprints.ShortComparisonBuilder { job.ensureActive() }
        // Temporary PCM is limited to one tiny local window and never cached.
        val pcm = FloatArray(8192)
        var count = 0
        return try {
            val decoded = decodeWindow(context, uri, AcousticWindow(startMs, endMs), {}) { sample ->
                check(count < pcm.size) { "Short comparison window exceeded limit" }
                pcm[count++] = sample
                builder.accept(sample)
            }
            if (!decoded) null else ShortWindow(builder.finish(), pcm.copyOf(count))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
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
                            val frameBytes=sampleBytes*channels
                            val frames=buffer.remaining()/frameBytes
                            val skip=((startUs-info.presentationTimeUs).coerceAtLeast(0)*rate/1_000_000L).coerceAtMost(frames.toLong()).toInt()
                            val until=if(endUs==null)frames else ((endUs-info.presentationTimeUs).coerceAtLeast(0)*rate/1_000_000L).coerceAtMost(frames.toLong()).toInt()
                            buffer.position(buffer.position()+skip*frameBytes)
                            if(!started&&until>skip){beginWindow((info.presentationTimeUs+skip*1_000_000L/rate)/1000);started=true}
                            repeat((until-skip).coerceAtLeast(0)) {
                                var mono = 0.0
                                repeat(channels) { mono += if (sampleBytes == 2) buffer.short/32768.0 else buffer.float.toDouble() }
                                sum += mono/channels; count++; phase += AcousticFingerprints.RATE
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
