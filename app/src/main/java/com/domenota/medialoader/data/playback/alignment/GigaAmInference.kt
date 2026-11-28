package com.domenota.medialoader.data.playback.alignment

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.util.Base64
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.domenota.medialoader.data.editor.GigaAmModelStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.security.MessageDigest

/** No network, native objects are scoped to a single explicit operation. */
internal object GigaAmInference {
    suspend fun align(context:Context,uri:Uri,lines:List<String>,allowed:()->Boolean,
        progress:(String,Int?)->Unit):CtcLyricsAligner.Result? {
        val coroutine=currentCoroutineContext()
        fun check(){coroutine.ensureActive();check(allowed()){ "Трек изменился или доступ отозван" }}
        check()
        val memory=ActivityManager.MemoryInfo().also{context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)}
        require(!memory.lowMemory && memory.availMem>=900L*1024*1024){"Недостаточно свободной памяти для GigaAM"}
        return GigaAmModelStore(context).withModel { model->
            check();progress("Декодирование аудио",null)
            val pcm=LyricsAudioDecoder.decode(context,uri,::check)
            val raw=context.assets.open("ai/gigaam_v3_filters.b64").bufferedReader().use{Base64.decode(it.readText(),Base64.DEFAULT)}
            require(raw.size==42496 && MessageDigest.getInstance("SHA-256").digest(raw).joinToString(""){"%02x".format(it.toInt() and 255)}==
                "83752fd2d47a6e0c50a2c32c27ff50c365da0f786004884cb4247d9e22d571bc"){"Фильтры GigaAM повреждены"}
            val bytes=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val window=FloatArray(320).also(bytes::get);val mel=FloatArray(161*64).also(bytes::get)
            val features=GigaAmFeatures(window,mel)
            val emissions=ArrayList<FloatArray>()
            val environment=OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { options->
                options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1)
                options.setCPUArenaAllocator(false);options.setMemoryPatternOptimization(false)
                environment.createSession(model.path,options).use { session->
                    var start=0
                    while(start<pcm.size) {
                        check();val end=minOf(start+320000,pcm.size)
                        val left=maxOf(0,start-48000);val right=minOf(pcm.size,end+48000+320)
                        if(right-left<320)break
                        val f=features.extract(pcm.slice(left,right),::check)
                        OnnxTensor.createTensor(environment,arrayOf(f)).use { input->
                            OnnxTensor.createTensor(environment,LongBuffer.wrap(longArrayOf(f[0].size.toLong())),longArrayOf(1)).use { length->
                                session.run(mapOf("features" to input,"feature_lengths" to length)).use { output->
                                    @Suppress("UNCHECKED_CAST")
                                    val rows=(output.get("log_probs").orElseThrow{IllegalStateException("Нет выхода GigaAM")}.value as Array<Array<FloatArray>>)[0]
                                    for((i,row)in rows.withIndex()){
                                        val position=left+i*640
                                        if(position>=start && position<end && position<pcm.size-160){
                                            require(position==emissions.size*640 && row.size==34){"Неверная временная шкала GigaAM"}
                                            emissions.add(row.copyOf())
                                        }
                                    }
                                }
                            }
                        }
                        progress("Анализ аудио",(end.toLong()*80/pcm.size).toInt());start=end
                    }
                }
            }
            check();progress("Проверка синхронизации",null)
            CtcLyricsAligner.align(emissions.toTypedArray(),lines.toTypedArray(),::check)
        }
    }
}
