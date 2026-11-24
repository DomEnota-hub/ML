package com.domenota.medialoader.core.logging

/** Fixed, non-media diagnostic fields. Only active in detailed performance mode.
 * Per-block limits and correlation IDs prevent repeated recompositions flooding the writer. */
internal enum class MusicRefreshBlock { CATALOG, RESUME, RANK, SMART, RECENT, BROWSE }

internal class MusicTraceWindow(private val intervalMs:Long=500L) {
    private var lastAt:Long?=null
    private var suppressed=0
    fun admit(now:Long):Int? {
        val previous=lastAt
        if(previous!=null && now-previous<intervalMs){suppressed++;return null}
        val skipped=suppressed
        suppressed=0;lastAt=now
        return skipped
    }
}

internal object MusicRefreshTrace {
    private val transitions=MusicRefreshBlock.entries.associateWith{MusicTraceWindow()}
    private val requests=MusicRefreshBlock.entries.associateWith{MusicTraceWindow()}
    fun transition(block:MusicRefreshBlock,ready:Boolean,usable:Boolean,loading:Boolean,error:Boolean,
        count:Int,guardPresent:Boolean,accessEpoch:Long=0L,privacyEpoch:Long=0L,reasonMask:Int=0) {
        if(!AppLog.isDetailedPerformance())return
        val now=android.os.SystemClock.elapsedRealtime()
        val skipped=synchronized(transitions){transitions.getValue(block).admit(now)}?:return
        AppLog.i("Perf","MUSIC_READ_MODEL block=$block ready=$ready usable=$usable loading=$loading error=$error count=$count guard=$guardPresent accessEpoch=$accessEpoch privacyEpoch=$privacyEpoch reasonMask=$reasonMask suppressed=$skipped")
    }
    fun begin(block:MusicRefreshBlock,retained:Boolean,revision:Long,count:Int):Request? {
        if(!AppLog.isDetailedPerformance())return null
        val now=android.os.SystemClock.elapsedRealtime()
        val skipped=synchronized(requests){requests.getValue(block).admit(now)}?:return null
        val id=AppLog.newOperationId("music")
        AppLog.i("Perf","MUSIC_REFRESH block=$block id=$id phase=START retained=$retained revision=$revision count=$count suppressed=$skipped")
        return Request(block,id,now)
    }
    class Request(private val block:MusicRefreshBlock,private val id:String,private val started:Long) {
        var outcome:Outcome=Outcome.DISCARDED
        fun close(){
            val ms=(android.os.SystemClock.elapsedRealtime()-started).coerceAtLeast(0L)
            AppLog.i("Perf","MUSIC_REFRESH block=$block id=$id phase=$outcome ms=$ms")
        }
    }
    enum class Outcome { PUBLISHED, CACHE, DISCARDED, CANCELLED, ERROR }
}
