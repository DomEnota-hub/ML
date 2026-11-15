package com.domenota.medialoader.data.playback
import org.junit.Assert.*
import org.junit.Test
class PlaybackDurationStateTest {
    @Test fun temporaryUnknownNeverErasesKnownDurationOfSameItem(){
        val id=PlaybackDurationIdentity("a","content://a",null)
        val state=PlaybackDurationState().update(id,180000)
        assertEquals(180000L,state.update(id,Long.MIN_VALUE+1).durationMs)
        assertEquals(180000L,state.update(id,0).durationMs)
        assertEquals(181000L,state.update(id,181000).durationMs)
    }
    @Test fun differentItemUriOrRevisionNeverInheritsOldDuration(){
        val id=PlaybackDurationIdentity("a","content://a",null)
        val state=PlaybackDurationState().update(id,180000)
        for(next in listOf(id.copy(id="b"),id.copy(uri="content://b"),id.copy(revision="new"),null))
            assertEquals(0L,state.update(next,0).durationMs)
        assertEquals(200000L,state.update(id.copy(revision="new"),0,200000).durationMs)
    }
}
