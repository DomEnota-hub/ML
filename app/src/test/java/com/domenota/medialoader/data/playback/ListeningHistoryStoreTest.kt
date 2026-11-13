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

    @Test fun ambiguousAliasCannotMergeTwoTracks() = runBlocking {
        assertNotNull(store.begin(setOf("app:a"),0,1000))
        assertNotNull(store.begin(setOf("app:b"),0,2000))
        assertNull(store.begin(setOf("app:a","app:b"),0,3000))
    }
}
