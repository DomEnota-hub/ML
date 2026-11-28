package com.domenota.medialoader.data.playback.alignment

import android.content.Context
import android.util.Base64
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.LongBuffer
import java.security.MessageDigest

/** Original onnx-asr fixed DSP filters, not another learned model. No files or network. */
internal class LyricsAudioResampler private constructor(
    rate:Int,private val session:OrtSession?,private val emit:(Float)->Unit,private val check:()->Unit
):Closeable {
    private val stream=session?.let { native->
        PcmResamplingWindow(rate,{input->
            check()
            val environment=OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(environment,arrayOf(input)).use { samples->
                OnnxTensor.createTensor(environment,LongBuffer.wrap(longArrayOf(input.size.toLong())),longArrayOf(1)).use { length->
                    native.run(mapOf("waveforms" to samples,"waveforms_lens" to length)).use { result->
                        @Suppress("UNCHECKED_CAST")
                        val rows=result.get("resampled").orElseThrow{IllegalStateException("Нет выхода фильтра")}.value as Array<FloatArray>
                        val lengths=result.get("resampled_lens").orElseThrow{IllegalStateException("Нет длины фильтра")}.value as LongArray
                        require(rows.size==1 && lengths.size==1 && lengths[0]==rows[0].size.toLong()){"Неверная длина PCM"}
                        rows[0]
                    }
                }
            }
        },{value->emit(value)},{check()})
    }
    fun accept(value:Float) {
        require(value.isFinite()){"PCM повреждён"}
        if(stream==null)emit(value)else stream.accept(value)
    }
    fun finish(){stream?.finish();check()}
    override fun close(){session?.close()}
    companion object {
        private val hashes=mapOf(
            8000 to "284a1fae00455c7a6335ea6d719d9cb84e3dc1bbabe4c9cf341ab79a2ee67873",
            11025 to "d2a57251995f9688a1172723852fc3c1b3b222eee73d8050525975deb9684c7f",
            22050 to "6a4f9ae1f6fd83a1b8ac24fdaf148a01a72269c5535649b8f3a68ffc8f126b7f",
            24000 to "4fd046461013b80c7bc9684eb0ccf2c905cbbd3cd8a2e1d2e1d4c4166af8dbbc",
            32000 to "a8ceb2777bc78989400d28607c9e8c75330ae1495631bab39daf24713c5511f1",
            44100 to "b2ff60730b462c48854a226bc60472b9e4a57d60f5c69e10a49920f3e5d0627f",
            48000 to "08f4c3190f2af9bb14ccf1c8f1f71b888cf0c733238380c7950051a5d5ddc83f"
        )
        fun supports(rate:Int)=rate==16000 || rate in hashes
        fun create(context:Context,rate:Int,emit:(Float)->Unit,check:()->Unit):LyricsAudioResampler {
            require(supports(rate)){"Частота аудио $rate Гц пока не поддерживается для синхронизации"}
            check()
            var opened:OrtSession?=null
            try {
                if(rate!=16000) {
                    val raw=context.assets.open("ai/resample_${rate/1000}_16.b64").bufferedReader().use{Base64.decode(it.readText(),Base64.DEFAULT)}
                    val digest=MessageDigest.getInstance("SHA-256").digest(raw).joinToString(""){"%02x".format(it.toInt() and 255)}
                    require(digest==hashes[rate]){"Фильтр пересчёта аудио повреждён"}
                    OrtSession.SessionOptions().use { options->
                        options.setIntraOpNumThreads(1);options.setInterOpNumThreads(1)
                        options.setCPUArenaAllocator(false);options.setMemoryPatternOptimization(false)
                        opened=OrtEnvironment.getEnvironment().createSession(raw,options)
                    }
                }
                return LyricsAudioResampler(rate,opened,emit,check)
            } catch(error:Throwable){runCatching{opened?.close()};throw error}
        }
    }
}
