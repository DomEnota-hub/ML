package com.domenota.medialoader.data.editor

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Optional, explicitly downloaded MIT model. No audio/text network requests. */
internal class GigaAmModelStore(context:Context) {
    private val folder=File(context.applicationContext.filesDir,"ai-models/gigaam-v3-ctc")
    private val model get()=File(folder,FILENAME)
    companion object {
        const val FILENAME="v3_ctc.int8.onnx"
        const val REVISION="322c3b29492673eb7d0b434bfa9dfb8653e34d02"
        const val SHA256="ceb61454e2e1a2dec5872cbac1de0fe0a4271d1148f6b26b5bda53ff30a12acd"
        const val BYTES=224721181L
        private val lock=Mutex()
    }
    fun installed()=model.isFile && model.length()==BYTES
    private suspend fun valid():Boolean {
        if(!installed())return false
        val digest=MessageDigest.getInstance("SHA-256")
        model.inputStream().buffered().use { input ->
            val b=ByteArray(65536)
            while(true){currentCoroutineContext().ensureActive();val n=input.read(b);if(n<0)break;digest.update(b,0,n)}
        }
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}==SHA256
    }
    suspend fun verifyInstalled()=withContext(Dispatchers.IO){lock.withLock{valid()}}
    suspend fun <T> withModel(block:suspend (File)->T):T=withContext(Dispatchers.IO){
        lock.withLock {
            if(!valid())throw IOException("Установите GigaAM в разделе AI-моделей")
            block(model)
        }
    }
    suspend fun install(onProgress:(Int)->Unit={}):Unit=withContext(Dispatchers.IO){lock.withLock {
        if(valid()){onProgress(100);return@withLock}
        check(folder.isDirectory||folder.mkdirs())
        if(StatFs(folder.path).availableBytes<512L*1024*1024)throw IOException("Для установки GigaAM нужно 512 МБ свободного места")
        val part=File(folder,FILENAME+".part");part.delete()
        val connection=URL("https://huggingface.co/istupakov/gigaam-v3-onnx/resolve/$REVISION/$FILENAME?download=true").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=20000;connection.readTimeout=60000;connection.instanceFollowRedirects=true
            if(connection.responseCode !in 200..299 || connection.contentLengthLong>BYTES)throw IOException("Не удалось загрузить GigaAM")
            val digest=MessageDigest.getInstance("SHA-256");var count=0L
            connection.inputStream.use { input->FileOutputStream(part).use { output->
                val b=ByteArray(65536)
                while(true){
                    currentCoroutineContext().ensureActive();val n=input.read(b);if(n<0)break
                    count+=n;if(count>BYTES)throw IOException("Неверный размер GigaAM")
                    digest.update(b,0,n);output.write(b,0,n);onProgress((count*100/BYTES).toInt().coerceAtMost(99))
                };output.fd.sync()
            }}
            if(count!=BYTES||digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}!=SHA256)throw IOException("Проверка SHA-256 GigaAM не пройдена")
            if(model.exists()&&!model.delete())throw IOException("Не удалось заменить GigaAM")
            if(!part.renameTo(model))throw IOException("Не удалось установить GigaAM")
            onProgress(100)
        }finally{connection.disconnect();part.delete()}
    }}
    suspend fun uninstall():Unit=withContext(Dispatchers.IO){lock.withLock {
        for(f in listOf(model,File(folder,FILENAME+".part")))if(f.exists()&&!f.delete())throw IOException("Не удалось удалить GigaAM")
    }}
}
