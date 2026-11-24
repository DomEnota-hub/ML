package com.domenota.medialoader.data.library

/** Scan-local optimization only. Both files must have a freshly computed full
 * SHA-256 and a known stamp; never seed this cache from persisted checkpoints.
 * No file paths, PCM, or evidence survive the scan. Not used for deletion. */
internal class FreshEncodedDigestCache(private val capacity:Int=4096) {
    init { require(capacity>0) }
    private data class Key(val sha:String,val bytes:Long,val epoch:Long)
    private val entries=object:LinkedHashMap<Key,String>(16,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Key,String>?)=size>capacity
    }
    fun find(sha:String,bytes:Long,modified:Long?,epoch:Long):String? =
        if(sha.isBlank()||bytes<=0||modified==null||modified<=0) null
        else entries[Key(sha,bytes,epoch)]
    fun remember(sha:String,bytes:Long,modified:Long?,epoch:Long,payload:String?){
        if(sha.isBlank()||bytes<=0||modified==null||modified<=0||payload.isNullOrBlank())return
        entries[Key(sha,bytes,epoch)]=payload
    }
}
