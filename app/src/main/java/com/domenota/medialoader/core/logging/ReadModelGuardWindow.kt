package com.domenota.medialoader.core.logging

internal enum class ReadModelGuardReason { PRIVACY, ACCESS, DISABLED }

/** Fixed-size buckets. No timer or secondary executor. */
internal class ReadModelGuardWindow(private val now:()->Long,private val windowMs:Long=15_000L){
    private data class Key(val kind:LibraryProjectionKind,val reason:ReadModelGuardReason)
    private data class Bucket(val first:Long,var last:Long,var repeated:Long=0)
    private val buckets=linkedMapOf<Key,Bucket>()
    private fun text(key:Key)="READ_MODEL_GUARD kind=${key.kind} reason=${key.reason} retained=false"
    private fun aggregate(key:Key,bucket:Bucket)=
        "${text(key)} repeatedSuppressed=${bucket.repeated} windowMs=${(bucket.last-bucket.first).coerceAtLeast(0L)}"
    @Synchronized fun record(kind:LibraryProjectionKind,reason:ReadModelGuardReason):List<String>{
        val key=Key(kind,reason)
        val at=now()
        val old=buckets[key]
        if(old!=null&&at-old.first<windowMs&&at>=old.first){
            old.last=at;old.repeated++;return emptyList()
        }
        val result=buildList{if(old!=null&&old.repeated>0)add(aggregate(key,old));add(text(key))}
        buckets[key]=Bucket(at,at)
        return result
    }
    @Synchronized fun drain(kind:LibraryProjectionKind?=null):List<String>{
        val keys=buckets.keys.filter{kind==null||it.kind==kind}
        return keys.mapNotNull{key->buckets.remove(key)?.takeIf{it.repeated>0}?.let{aggregate(key,it)}}
    }
    @Synchronized fun clear(){buckets.clear()}
}
