package com.domenota.medialoader.data.deferred

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Separate identities for a new download and multiple recoveries from one publication. */
data class DeferredLink(val id:String,val url:String,val recoveryHistoryId:String?=null)

class DeferredLinkStore(context:Context){
    private val prefs=context.getSharedPreferences("deferred_download_links",Context.MODE_PRIVATE)
    private fun valid(link:String):Boolean=runCatching{
        val uri=Uri.parse(link)
        uri.scheme in listOf("https","http")&&!uri.host.isNullOrBlank()
    }.getOrDefault(false)
    private var unreadable=false
    private fun read():List<DeferredLink>{
        val versioned=prefs.getString("entries_v2",null)
        // Never reinterpret a broken v2 recovery as an ordinary new download.
        return runCatching{
            val json=JSONArray(versioned?:prefs.getString("links","[]"))
            (0 until json.length()).mapNotNull{index->
                if(versioned==null){
                    val url=json.getString(index).trim()
                    DeferredLink(url,url).takeIf{valid(url)}
                }else{
                    val obj=json.getJSONObject(index)
                    val url=obj.getString("url")
                    val id=obj.getString("id")
                    require(obj.has("recoveryHistoryId"))
                    val recovery=if(obj.isNull("recoveryHistoryId"))null else obj.getString("recoveryHistoryId")
                    require(id.isNotBlank()&&valid(url)&&(recovery==null||recovery.isNotBlank()))
                    require(if(recovery==null)id==url else id!=url)
                    DeferredLink(id,url,recovery)
                }
            }.distinctBy{it.id}
        }.getOrElse{unreadable=true;emptyList()}
    }
    private val state=MutableStateFlow(read())
    val entries=state.asStateFlow()
    // Retain the old URL-only view for existing callers; never use it for routing.
    private val legacyState=MutableStateFlow(state.value.map{it.url}.distinct())
    val links=legacyState.asStateFlow()

    @Synchronized fun add(raw:String,recoveryHistoryId:String?=null):Boolean{
        val link=raw.trim()
        if(!valid(link)||recoveryHistoryId?.isBlank()==true)return false
        if(state.value.any{it.url==link&&it.recoveryHistoryId==recoveryHistoryId})return true
        val id=if(recoveryHistoryId==null)link else java.util.UUID.randomUUID().toString()
        return write(state.value+DeferredLink(id,link,recoveryHistoryId))
    }
    @Synchronized fun remove(id:String):Boolean=write(state.value.filterNot{it.id==id})
    @Synchronized fun complete(url:String,recoveryHistoryId:String?):Boolean=
        write(state.value.filterNot{it.url==url&&it.recoveryHistoryId==recoveryHistoryId})
    private fun write(entries:List<DeferredLink>):Boolean{
        if(unreadable)return false
        val json=JSONArray()
        entries.forEach{entry->json.put(JSONObject().put("id",entry.id).put("url",entry.url)
            .put("recoveryHistoryId",entry.recoveryHistoryId?:JSONObject.NULL))}
        if(!prefs.edit().putString("entries_v2",json.toString()).remove("links").commit())return false
        state.value=entries
        legacyState.value=entries.map{it.url}.distinct()
        return true
    }
    companion object {
        fun source(link: String): String {
            val host = Uri.parse(link).host.orEmpty().lowercase().removePrefix("www.")
            fun site(domain: String) = host == domain || host.endsWith(".$domain")
            return when {
                site("instagram.com") -> "Instagram"
                site("youtube.com") || site("youtu.be") -> "YouTube"
                site("vk.com") || site("vk.ru") || site("vkvideo.ru") -> "VK"
                site("rutube.ru") -> "RUTUBE"
                else -> host.ifBlank { "Ссылка" }
            }
        }
    }
}
