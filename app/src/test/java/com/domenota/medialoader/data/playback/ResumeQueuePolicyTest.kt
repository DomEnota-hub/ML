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
}
