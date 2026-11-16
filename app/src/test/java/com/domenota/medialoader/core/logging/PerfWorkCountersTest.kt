package com.domenota.medialoader.core.logging

import org.junit.Assert.*
import org.junit.Test

class PerfWorkCountersTest {
    @Test fun aggregatesFixedCategoriesAndMaximum(){
        var now=0L;val counters=PerfWorkCounters{now}
        val first=counters.start();now=7_000_000;counters.record(PerfWork.RESUME_MEMBERSHIP,first)
        val second=counters.start();now+=3_000_000;counters.record(PerfWork.RESUME_MEMBERSHIP,second)
        assertTrue(counters.summary().contains("RESUME_MEMBERSHIP[n=2,totalMs=10,maxMs=7]"))
        assertTrue(counters.summary().contains("PLAYER_QUEUE_REUSE[n=0,totalMs=0,maxMs=0]"))
    }
}
