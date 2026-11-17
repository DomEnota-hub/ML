package com.domenota.medialoader.core.logging

import java.util.concurrent.atomic.AtomicLongArray

/** Fixed, privacy-safe work categories; no locators, IDs, text or per-item logging. */
internal enum class PerfWork {
    RESUME_MEMBERSHIP, PLAYER_QUEUE_REUSE, PLAYER_QUEUE_REPLACE, PLAYER_QUEUE_CAPTURE,
    VIEWER_QUEUE, GALLERY_DATES, PHONE_SCAN, LOG_PARSE, LOG_FILTER, COLLECTION_COVER, DUPLICATE_INDEX,
    AUDIO_METADATA_REFRESH, PHONE_SCAN_SKIPPED, PHONE_SCAN_RECOVERY
}
internal class PerfWorkCounters(private val now:()->Long=System::nanoTime) {
    private val counts=AtomicLongArray(PerfWork.entries.size*3)
    fun start():Long=now()
    fun record(kind:PerfWork,started:Long){
        val duration=(now()-started).coerceAtLeast(0L)/1_000_000L
        val slot=kind.ordinal*3
        counts.incrementAndGet(slot);counts.addAndGet(slot+1,duration)
        while(true){val previous=counts.get(slot+2);if(previous>=duration||counts.compareAndSet(slot+2,previous,duration))break}
    }
    fun summary():String=PerfWork.entries.joinToString(" "){kind->
        val slot=kind.ordinal*3
        "${kind.name}[n=${counts.get(slot)},totalMs=${counts.get(slot+1)},maxMs=${counts.get(slot+2)}]"
    }
}
internal object DetailedPerformance {
    private val counters=PerfWorkCounters()
    fun begin():Long=if(AppLog.isDetailedPerformance())counters.start() else Long.MIN_VALUE
    fun end(kind:PerfWork,started:Long){if(started!=Long.MIN_VALUE)counters.record(kind,started)}
    inline fun <T> measure(kind:PerfWork,action:()->T):T {
        val started=begin();return try{action()}finally{end(kind,started)}
    }
    fun summary()=counters.summary()
}
