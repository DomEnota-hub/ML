package com.domenota.medialoader.data.playback

import org.junit.Assert.*
import org.junit.Test

class ResumeQueuePolicyTest {
    private fun session(id:String="q",index:Int=1)=ResumeQueueSession(id,null,listOf(setOf("app:a"),setOf("old:b"),setOf("app:c")),index,102_000,180_000,false,0,1f,100)
    private fun c(id:String,vararg keys:String)=SmartTrackCandidate(id,keys.toSet(),null,false)
    @Test fun restoreUsesKnownAliasesAndPreservesQueueOrderAndPosition(){
        val result=ResumeQueuePolicy.resolve(session(),listOf(c("c","app:c"),c("a","app:a"),c("new-b","new:b")),mapOf("old:b" to "b","new:b" to "b"))
        assertEquals(listOf("a","new-b","c"),result.availableIds)
        assertEquals("new-b",result.currentId);assertEquals(1,result.currentIndex)
        assertEquals(102_000L,ResumeQueuePolicy.clampedPosition(result.session,180_000))
    }
    @Test fun missingEntriesNeverChangeTheCurrentTrackSilently(){
        val result=ResumeQueuePolicy.resolve(session(),listOf(c("b","old:b"),c("c","app:c")),emptyMap())
        assertEquals(1,result.missing);assertEquals(0,result.currentIndex)
        assertEquals("b",result.currentId)
        assertNull(ResumeQueuePolicy.resolve(session(),listOf(c("a","app:a"),c("c","app:c")),emptyMap()).currentId)
    }
    @Test fun queueLimitAndContextAreStable(){
        val entries=session().entries
        assertEquals(ResumeQueuePolicy.contextId(null,entries),ResumeQueuePolicy.contextId(null,entries))
        assertNotEquals(ResumeQueuePolicy.contextId(null,entries),ResumeQueuePolicy.contextId(null,entries.reversed()))
        assertEquals("playlist:p",ResumeQueuePolicy.contextId("p",entries))
        assertNull(ResumeQueuePolicy.contextId(null,listOf(emptySet())))
        var all=emptyList<ResumeQueueSession>()
        for(i in 1..12)all=ResumeQueuePolicy.keep(all,session("$i"))
        assertEquals(8,all.size);assertEquals("12",all.first().id)
        all=ResumeQueuePolicy.keep(all,session("10"));assertEquals(8,all.size);assertEquals("10",all.first().id)
    }
    @Test fun duplicateTracksRemainSeparateOccurrencesAndRemovalPreservesCurrentPosition() {
        val saved=session().copy(entries=listOf(setOf("app:a"),setOf("app:a"),setOf("app:c")))
        val resolved=ResumeQueuePolicy.resolve(saved,listOf(c("a","app:a"),c("c","app:c")),emptyMap())
        assertEquals(listOf("a","a","c"),resolved.ids)
        val edited=requireNotNull(ResumeQueuePolicy.removeOccurrences(resolved,setOf(saved.occurrenceIds[0])))
        assertEquals(0,edited.index);assertEquals(102_000L,edited.positionMs)
        assertEquals(saved.occurrenceIds.drop(1),edited.occurrenceIds)
        assertEquals(listOf(setOf("app:a"),setOf("app:c")),edited.entries)
    }
    @Test fun removingCurrentChoosesNextAvailableThenPreviousAndResetsOnlyChangedPosition() {
        val saved=session(index=0)
        val resolved=ResolvedResumeQueue(saved,listOf("a",null,"c"))
        val next=requireNotNull(ResumeQueuePolicy.removeOccurrences(resolved,setOf(saved.occurrenceIds[0])))
        assertEquals(1,next.index);assertEquals(0L,next.positionMs)
        val last=session(index=2)
        val previous=requireNotNull(ResumeQueuePolicy.removeOccurrences(ResolvedResumeQueue(last,listOf("a",null,"c")),setOf(last.occurrenceIds[2])))
        assertEquals(0,previous.index)
        assertNull(ResumeQueuePolicy.removeOccurrences(resolved,saved.occurrenceIds.toSet()))
        assertEquals(saved,ResumeQueuePolicy.removeOccurrences(resolved,emptySet()))
        assertEquals(saved.shuffle,next.shuffle);assertEquals(saved.repeat,next.repeat)
    }
}
