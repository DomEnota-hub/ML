package com.domenota.medialoader.data.library

import android.content.Context
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Reuse only exact, typed MediaStore audio locators with a verified store stamp.
 * Current Room rows provide all metadata; this stores identity aliases only. */
internal class AppAudioIdentityCache(context:Context) {
    private val cache=PersistentProjectionCache(context,"app-audio-identities")
    private fun eligible(row:DownloadEntity):Boolean = !row.isPrivate && !row.hidden && row.state==DownloadState.COMPLETED &&
        row.mediaType==MediaType.AUDIO && row.savedUri?.let { CollectionAddressResolver.isTypedPhoneAddress(it) && it.contains("/audio/media/") }==true &&
        (row.sizeBytes?:0)>0 && row.createdAtEpochMillis>0
    private fun key(row:DownloadEntity)=PersistentProjectionCache.digest(JSONArray(listOf(row.id,row.savedUri,row.sizeBytes,row.createdAtEpochMillis)).toString())
    private fun guard(stamp:PhoneStoreStamp,privacy:String):String? = if(stamp.versions==null||stamp.generation==null)null else
        PersistentProjectionCache.digest(JSONArray(listOf(stamp.versions,stamp.generation,privacy)).toString())
    suspend fun read(rows:List<DownloadEntity>,stamp:PhoneStoreStamp,privacy:String,blocked:Set<String>):Map<String,Set<String>> {
        val tag=guard(stamp,privacy)?:return emptyMap()
        val value=cache.read("audio",tag)?:return emptyMap()
        return try { buildMap {
            for(row in rows) if(eligible(row)&&row.id !in blocked){
                val uri=requireNotNull(row.savedUri);val aliases=value.optJSONArray(key(row))?:continue
                val keys=(0 until aliases.length()).mapTo(mutableSetOf()){aliases.getString(it)}
                if(keys.none(blocked::contains)&&PublicMediaIdentity.uriKeys(uri).all(keys::contains)&&keys.any{it.startsWith("file-stat:")})put(uri,keys)
            }
        } } catch(e:CancellationException){throw e}catch(_:Exception){emptyMap()}
    }
    suspend fun write(refs:List<LibraryMediaRef>,stamp:PhoneStoreStamp,privacy:String,blocked:Set<String>,valid:()->Boolean={true}) {
        val tag=guard(stamp,privacy)?:return
        val value=JSONObject()
        for(ref in refs) if(eligible(ref.item)&&!collectionRefBlocked(ref,blocked)&&ref.aliases.any{it.startsWith("file-stat:")})
            value.put(key(ref.item),JSONArray(ref.aliases.filter{it!=ref.key}.sorted()))
        cache.write("audio",tag,value,valid)
    }
}
