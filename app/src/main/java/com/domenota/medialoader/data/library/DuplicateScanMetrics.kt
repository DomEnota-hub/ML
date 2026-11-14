package com.domenota.medialoader.data.library

import android.os.SystemClock
import android.util.Log

/** Aggregate timings only: no URI, file name, title or private media metadata. */
internal class DuplicateScanMetrics(private val kind:String) {
    private val elapsed=linkedMapOf<String,Long>()
    private val counts=linkedMapOf<String,Int>()
    suspend fun <T> measure(stage:String, block:suspend ()->T):T {
        val start=SystemClock.elapsedRealtime()
        try{return block()}finally{
            elapsed[stage]=(elapsed[stage]?:0L)+SystemClock.elapsedRealtime()-start
            counts[stage]=(counts[stage]?:0)+1
        }
    }
    fun report(){
        Log.i("DuplicateScanMetrics", "$kind "+elapsed.entries.joinToString(" "){(stage,ms)->"$stage=${ms}ms/${counts[stage]}"})
    }
}
