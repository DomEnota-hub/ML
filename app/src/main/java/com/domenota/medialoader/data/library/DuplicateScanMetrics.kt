package com.domenota.medialoader.data.library

import android.os.SystemClock
import android.util.Log
import com.domenota.medialoader.core.logging.AppLog

/** Aggregate timings only: no URI, file name, title or private media metadata. */
internal class DuplicateScanMetrics(private val kind:String) {
    private val started=SystemClock.elapsedRealtime()
    private val elapsed=linkedMapOf<String,Long>()
    private val counts=linkedMapOf<String,Int>()
    suspend fun <T> measure(stage:String, block:suspend ()->T):T {
        val start=SystemClock.elapsedRealtime()
        try{return block()}finally{
            elapsed[stage]=(elapsed[stage]?:0L)+SystemClock.elapsedRealtime()-start
            counts[stage]=(counts[stage]?:0)+1
        }
    }
    fun hit(stage:String){counts[stage]=(counts[stage]?:0)+1;elapsed.putIfAbsent(stage,0L)}
    fun report(phase:String="completed",processed:Int=0,skipped:Int=0){
        val message="$kind phase=$phase processed=$processed skipped=$skipped elapsedMs=${SystemClock.elapsedRealtime()-started} "+elapsed.entries.joinToString(" "){(stage,ms)->"$stage=${ms}ms/${counts[stage]}"}
        Log.i("DuplicateScanMetrics",message)
        AppLog.i("DuplicateScanMetrics",message)
    }
}
