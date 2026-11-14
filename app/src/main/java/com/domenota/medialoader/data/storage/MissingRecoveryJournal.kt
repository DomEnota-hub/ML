package com.domenota.medialoader.data.storage

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.AtomicFile
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.download.MissingRecoveryFailure
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Opt-in for the existing transfer engines. Ordinary downloads never see this context. */
internal class MissingRecoveryPublication(
    val prepare:suspend (File,String)->Unit,
    val created:suspend (Uri)->Unit,
):AbstractCoroutineContextElement(Key){
    companion object Key:CoroutineContext.Key<MissingRecoveryPublication>
}

internal data class MissingRecoveryOutput(
    val historyId:String,val oldUri:String?,val uri:String?,val bytes:Long,val sha256:String,
)

/** One public recovery at a time, protected by the repository's operation gate.
 * Record only locators allocated by StorageManager for this recovery, never candidates
 * from a scan or user-owned arbitrary files. A corrupt journal fails closed. */
internal class MissingRecoveryJournal(private val context:Context){
    private val file=AtomicFile(File(context.noBackupFilesDir,"missing-recovery-output-v1.json"))
    fun read():MissingRecoveryOutput?{
        if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return null
        try{
            val obj=JSONObject(file.openRead().bufferedReader().use{it.readText()})
            require(obj.getInt("version")==1)
            return MissingRecoveryOutput(obj.getString("historyId"),
                if(obj.isNull("oldUri"))null else obj.getString("oldUri"),
                if(obj.isNull("uri"))null else obj.getString("uri"),
                obj.getLong("bytes"),obj.getString("sha256")).also{
                require(it.historyId.isNotBlank()&&it.bytes>0&&it.sha256.matches(Regex("[0-9a-f]{64}")))
                require(it.uri==null||it.uri!=it.oldUri)
            }
        }catch(e:Exception){throw MissingRecoveryFailure("Не удалось проверить журнал восстановления. Исходные записи сохранены.")}
    }
    private fun write(value:MissingRecoveryOutput){
        val json=JSONObject().put("version",1).put("historyId",value.historyId)
            .put("oldUri",value.oldUri?:JSONObject.NULL).put("uri",value.uri?:JSONObject.NULL)
            .put("bytes",value.bytes).put("sha256",value.sha256)
        val out=file.startWrite()
        try{out.write(json.toString().toByteArray(Charsets.UTF_8));file.finishWrite(out)}
        catch(error:Throwable){file.failWrite(out);throw error}
    }
    fun clear(){file.delete()}
    fun publication(row:DownloadEntity)=MissingRecoveryPublication(
        prepare={source,mime->
            validateStage(row.mediaType,source,mime)
            val fingerprint=fingerprint(source.inputStream())
            check(fingerprint.first==source.length()&&fingerprint.first>0){"Восстановленный файл неполон"}
            check(read()==null){"Не завершена предыдущая публикация"}
            write(MissingRecoveryOutput(row.id,row.savedUri,null,fingerprint.first,fingerprint.second))
        },
        created={uri->
            val before=requireNotNull(read()){"Нет подготовленного файла"}
            check(before.historyId==row.id&&uri.toString()!=row.savedUri){"Неверный адрес восстановления"}
            write(before.copy(uri=uri.toString()))
        }
    )
    suspend fun verify(uri:String):Long{
        val record=requireNotNull(read()){"Нет записи публикации"}
        check(record.uri==uri){"Адрес восстановленного файла не совпадает"}
        val actual=readFingerprint(uri)
        require(actual.first==record.bytes&&actual.second==record.sha256){"Контрольная сумма восстановленного файла не совпала"}
        return actual.first
    }
    /** Cleanup after cancellation or process death. An unrelated/referenced file is never deleted.
     * A completed unreferenced output must still match the staged fingerprint; an unfinished
     * MediaStore output may be removed only while it is an app-owned pending row.
     * Otherwise retain the journal and block another write rather than create duplicates. */
    suspend fun reconcile(referenceCount:suspend(String)->Int, delete:suspend(String)->Boolean){
        val record=read()?:return
        val uri=record.uri
        if(uri==null){clear();return}
        if(referenceCount(uri)>0){clear();return}
        val status=SavedFilePresence.check(context,uri)
        if(status==SavedFileStatus.MISSING){clear();return}
        val verified=try{
            val actual=readFingerprint(uri)
            actual.first==record.bytes&&actual.second==record.sha256
        }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){false}
        if(!verified&&!ownedPending(uri))throw MissingRecoveryFailure(
            "Остался неподтверждённый файл восстановления. Повторная запись остановлена, исходная запись сохранена.")
        if(!delete(uri)&&SavedFilePresence.check(context,uri)!=SavedFileStatus.MISSING)
            throw MissingRecoveryFailure("Не удалось удалить незавершённую копию восстановления. Исходная запись сохранена.")
        if(Uri.parse(uri).scheme=="file")Uri.parse(uri).path?.let{path->
            android.media.MediaScannerConnection.scanFile(context,arrayOf(path),null,null)
        }
        clear()
    }
    private fun ownedPending(raw:String):Boolean{
        if(Build.VERSION.SDK_INT<29)return false
        val uri=Uri.parse(raw)
        if(uri.authority!=MediaStore.AUTHORITY)return false
        return runCatching{
            context.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.IS_PENDING),null,null,null)?.use{c->
                c.count==1&&c.moveToFirst()&&c.getString(0)==context.packageName&&c.getInt(1)==1
            }==true
        }.getOrDefault(false)
    }
    private suspend fun readFingerprint(raw:String):Pair<Long,String>{
        val uri=Uri.parse(raw)
        val input=if(uri.scheme=="file")File(requireNotNull(uri.path)).inputStream()
            else context.contentResolver.openInputStream(uri)?:error("Файл недоступен")
        return fingerprint(input)
    }
    private suspend fun fingerprint(input:InputStream):Pair<Long,String>{
        val hash=MessageDigest.getInstance("SHA-256")
        var bytes=0L
        input.use{stream->
            val buffer=ByteArray(128*1024)
            while(true){
                currentCoroutineContext().ensureActive()
                val count=stream.read(buffer)
                if(count<0)break
                hash.update(buffer,0,count);bytes+=count
            }
        }
        return bytes to hash.digest().joinToString(""){"%02x".format(it)}
    }
    private fun validateStage(type:MediaType,source:File,mime:String){
        require(source.isFile&&source.length()>0){"Восстановленный файл пуст"}
        if(type==MediaType.PHOTO){
            require(mime.startsWith("image/")){"Тип файла не совпадает"}
            val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeFile(source.absolutePath,options)
            require(options.outWidth>0&&options.outHeight>0){"Восстановленное изображение повреждено"}
        }else{
            require(mime.startsWith(if(type==MediaType.VIDEO)"video/" else "audio/")){"Тип файла не совпадает"}
            val retriever=MediaMetadataRetriever()
            try{
                retriever.setDataSource(source.absolutePath)
                val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val kind=if(type==MediaType.VIDEO)MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO
                    else MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
                require(duration!=null&&duration>0&&retriever.extractMetadata(kind)=="yes"){"Восстановленный медиафайл повреждён"}
            }finally{retriever.release()}
        }
    }
}
