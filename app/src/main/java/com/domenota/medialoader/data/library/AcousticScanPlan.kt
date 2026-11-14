package com.domenota.medialoader.data.library

/** Sampling is independent of metadata. Expanded mode covers the complete stream. */
internal data class AcousticWindow(val startMs:Long,val endMs:Long?)
internal object AcousticScanPlan {
    fun windows(durationMs:Long?,expanded:Boolean=false):List<AcousticWindow>{
        val duration=durationMs?.takeIf{it>0}
        if(expanded||duration==null||duration<=45_000L)return listOf(AcousticWindow(0,null))
        val count=when{duration<=90_000L->2;duration<=360_000L->3;else->5}
        return (0 until count).map{index->
            val start=(duration-15_000L)*index/(count-1)
            AcousticWindow(start,start+15_000L)
        }
    }
}
