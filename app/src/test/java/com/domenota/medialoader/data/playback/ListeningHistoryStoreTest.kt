package com.domenota.medialoader.data.playback

import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ListeningHistoryStoreTest {
    private lateinit var store: ListeningHistoryStore
    @Before fun setup() {
        RuntimeEnvironment.getApplication().deleteDatabase("listening_history.db")
        store = ListeningHistoryStore.forTest(RuntimeEnvironment.getApplication())
    }
    @After fun close() { store.closeForTest() }

    @Test fun sameSessionIdNeverDoubleCountsAndExclusionSurvivesClear() = runBlocking {
        val keys = setOf("app:a","file-stat:a")
        val first = requireNotNull(store.begin(keys,0,1000))
        val credited = first.copy(lastAt=31_000,mediaMs=30_000,wallMs=30_000,creditedAt=31_000,position=30_000)
        store.save(credited); store.save(credited)
        assertTrue(store.selection(mapOf("a" to keys),ListeningPeriod.ALL,50_000).ranked.isEmpty())
        val resumed = requireNotNull(store.begin(keys,31_000,32_000))
        assertEquals(first.sessionId,resumed.sessionId)
        val second = requireNotNull(store.begin(keys,0,40_000))
        assertNotEquals(first.sessionId,second.sessionId)
        store.save(second.copy(lastAt=70_000,mediaMs=30_000,wallMs=30_000,creditedAt=70_000,position=30_000,closed=true))
        assertEquals(2L,store.selection(mapOf("renamed" to setOf("app:a")),ListeningPeriod.ALL,80_000).ranked.single().plays)
        store.setExcluded(first.trackId,true)
        val excluded = store.selection(mapOf("renamed" to setOf("app:a")),ListeningPeriod.ALL,80_000)
        assertTrue(excluded.ranked.isEmpty()); assertEquals("renamed",excluded.excluded.single().rowId)
        store.clearHistory(); store.save(credited)
        assertTrue(store.selection(mapOf("renamed" to keys),ListeningPeriod.ALL,80_000).ranked.isEmpty())
        assertEquals(1,store.selection(emptyMap(),ListeningPeriod.ALL,80_000).excluded.size)
        store.restoreAll()
        assertTrue(store.selection(emptyMap(),ListeningPeriod.ALL,80_000).excluded.isEmpty())
    }

    @Test fun smartViewsReuseOriginalHistoryAndRespectPeriods() = runBlocking {
        val keys=setOf("app:old")
        val now=100L*SmartMusicPolicy.DAY_MS
        val oldAt=now-40*SmartMusicPolicy.DAY_MS
        val p=requireNotNull(store.begin(keys,0,oldAt))
        store.save(p.copy(lastAt=oldAt+30_000,creditedAt=oldAt+30_000,mediaMs=30_000,wallMs=15_000,closed=true))
        val candidates=listOf(SmartTrackCandidate("old",keys,"Artist",true),SmartTrackCandidate("new",setOf("app:new"),"Other",false))
        val month=store.smartSnapshot(candidates,SmartStatsPeriod.MONTH,now)
        assertEquals("old",month.forgotten.single().rowId)
        assertEquals(0L,month.plays); assertEquals(0L,month.wallMs)
        assertEquals(setOf("old","new"),month.mix.map {it.rowId}.toSet())
        val all=store.smartSnapshot(candidates,SmartStatsPeriod.YEAR,now)
        assertEquals(1L,all.plays); assertEquals(15_000L,all.wallMs)
        assertEquals(12,all.chart.size); assertEquals(1L,all.chart.sumOf { it.plays }); assertEquals(15_000L,all.chart.sumOf { it.wallMs })
        store.setExcluded(p.trackId,true)
        val excluded=store.smartSnapshot(candidates,SmartStatsPeriod.YEAR,now)
        assertTrue(excluded.forgotten.isEmpty()); assertEquals(listOf("new"),excluded.mix.map{it.rowId})
        assertEquals(1L,excluded.plays)
        store.clearHistory()
        assertEquals(0L,store.smartSnapshot(candidates,SmartStatsPeriod.YEAR,now).plays)
    }

    @Test fun ambiguousAliasCannotMergeTwoTracks() = runBlocking {
        assertNotNull(store.begin(setOf("app:a"),0,1000))
        assertNotNull(store.begin(setOf("app:b"),0,2000))
        assertNull(store.begin(setOf("app:a","app:b"),0,3000))
    }
}
