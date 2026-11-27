package com.domenota.medialoader.data.playback

import com.domenota.medialoader.data.tasks.TaskFailure
import java.io.File
import java.util.concurrent.CancellationException

/** Same persisted cache used by the player, editor and background sweep.
 * A successful decode alone is NOT a successful persistent preparation. */
internal data class WaveformPreparation(
    val peaks:List<Float>,
    val outcome:WaveformOutcome,
    val failure:TaskFailure?=null
)
internal object TimelineWaveformDiskCache {
    private fun valid(peaks:List<Float>,count:Int)=count>0&&peaks.size==count&&peaks.all{it.isFinite()&&it in 0f..1f}
    fun read(file:File,count:Int):List<Float>? {
        if(count<=0||!file.isFile||file.length() !in 1L..count*32L)return null
        return runCatching {
            val parts=file.readText().split(',')
            if(parts.size!=count)return null
            parts.map{it.toFloat()}.takeIf{valid(it,count)}
        }.getOrNull()
    }
    fun prepare(file:File,count:Int,cancelled:()->Boolean={false},
        sameVersion:()->Boolean={true},decode:()->List<Float>):WaveformPreparation {
        fun active(){if(cancelled())throw CancellationException("Waveform stopped")}
        active()
        read(file,count)?.let{active();return WaveformPreparation(it,WaveformOutcome.CACHED)}
        return WaveformDecodeGate.shared.decode(cancelled) {
            active()
            read(file,count)?.let{return@decode WaveformPreparation(it,WaveformOutcome.CACHED)}
            val peaks=try {decode()} catch(e:CancellationException){throw e} catch(_:Exception){emptyList()}
            active()
            if(!valid(peaks,count))return@decode WaveformPreparation(emptyList(),WaveformOutcome.FAILED,TaskFailure.DECODE)
            if(!sameVersion())return@decode WaveformPreparation(emptyList(),WaveformOutcome.FAILED,TaskFailure.IO)
            active()
            val saved=runCatching {
                val parent=requireNotNull(file.parentFile)
                parent.mkdirs()
                val temporary=File.createTempFile("waveform-",".tmp",parent)
                try {
                    temporary.outputStream().use { stream ->
                        stream.write(peaks.joinToString(",").toByteArray(Charsets.UTF_8))
                        stream.fd.sync()
                    }
                    active()
                    check(temporary.renameTo(file))
                    check(read(file,count)==peaks)
                    true
                } finally { temporary.delete() }
            }.getOrElse { if(it is CancellationException)throw it; false }
            if(!saved)WaveformPreparation(peaks,WaveformOutcome.FAILED,TaskFailure.CACHE_WRITE)
            else WaveformPreparation(peaks,WaveformOutcome.GENERATED)
        }
    }
}
