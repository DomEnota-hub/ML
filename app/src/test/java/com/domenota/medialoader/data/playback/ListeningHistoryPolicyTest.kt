package com.domenota.medialoader.data.playback

import org.junit.Assert.*
import org.junit.Test

class ListeningHistoryPolicyTest {
    private fun counter(duration: Long = 180_000L, speed: Float = 1f) =
        ListeningCounter(ListeningProgress("session","track",100,100),0,0,true,speed,duration)

    @Test fun continuousPlaybackCountsOnlyOnceAndPauseDoesNotCount() {
        val c = counter()
        for (i in 1L..20L) c.sample(i*1000,i*1000,100+i*1000,true,1f)
        c.sample(20_000,21_000,21_100,false,1f)
        c.sample(20_000,100_000,100_100,true,1f)
        for (i in 1L..10L) c.sample(20_000+i*1000,100_000+i*1000,100_100+i*1000,true,1f)
        assertEquals(30_000L,c.progress.mediaMs)
        val credited = c.progress.creditedAt
        assertNotNull(credited)
        c.sample(31_000,111_000,111_100,true,1f)
        assertEquals(credited,c.progress.creditedAt)
    }
    @Test fun seekAndBufferingDoNotAddUnheardTime() {
        val c = counter()
        c.sample(1000,1000,1100,true,1f)
        c.reset(179_000,1001,true,1f)
        c.sample(180_000,2001,2101,false,1f)
        assertEquals(2000L,c.progress.mediaMs)
        assertNull(c.progress.creditedAt)
    }
    @Test fun shortUnknownAndFastPlayback() {
        val c = counter(20_000)
        for (i in 1L..10L) c.sample(i*1000,i*1000,100+i*1000,true,1f)
        assertNotNull(c.progress.creditedAt)
        val unknown = counter(-1)
        for(i in 1L..29L) unknown.sample(i*1000,i*1000,100+i*1000,true,1f)
        assertNull(unknown.progress.creditedAt)
        unknown.sample(30_000,30_000,30_100,true,1f)
        assertNotNull(unknown.progress.creditedAt)
        val fast = counter(speed=2f)
        for(i in 1L..15L) fast.sample(i*2000,i*1000,100+i*1000,true,2f)
        assertEquals(30_000L,fast.progress.mediaMs); assertEquals(15_000L,fast.progress.wallMs)
        assertNotNull(fast.progress.creditedAt)
    }
    @Test fun rankAndPeriodBoundaries() {
        val rows = listOf(ListeningRank("a","a",2,3),ListeningRank("b","b",2,4),
            ListeningRank("c","c",1,9),ListeningRank("d","d",3,1))
        assertEquals(listOf("d","b","a"),ListeningHistoryPolicy.rank(rows).map { it.rowId })
        assertEquals(7*86_400_000L,ListeningPeriod.WEEK.cutoff(14*86_400_000L))
        assertEquals(0L,ListeningPeriod.ALL.cutoff(Long.MAX_VALUE))
        assertEquals(30,ListeningHistoryPolicy.rank((1..50).map { ListeningRank("$it","$it",2,it.toLong()) }).size)
    }
    @Test fun resumeOnlyNearSamePositionAndRecentUnclosedSession() {
        val p = ListeningProgress("s","t",100,1000,position=34_000,creditedAt=900)
        assertTrue(ListeningHistoryPolicy.mayResume(p,35_000,2000))
        assertFalse(ListeningHistoryPolicy.mayResume(p,0,2000))
        assertFalse(ListeningHistoryPolicy.mayResume(p.copy(closed=true),35_000,2000))
    }
}
