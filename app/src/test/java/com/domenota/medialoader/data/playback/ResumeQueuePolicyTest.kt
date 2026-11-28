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
    @Test fun exactPhysicalIdentitySurvivesOnlyUnrelatedAliasAmbiguity(){
        val saved=session().copy(entries=listOf(setOf("app:a"),setOf("file-stat:exact"),setOf("app:c")))
        val rows=listOf(c("a","app:a"),c("b","file-stat:exact","shared"),c("other","shared"),c("c","app:c"))
        val resolved=ResumeQueuePolicy.resolve(saved,rows,emptyMap())
        assertEquals("b",resolved.currentId)
        assertEquals(ResumeAvailability.VERIFIED,resolved.availability[1])
        val ambiguous=ResumeQueuePolicy.resolve(saved.copy(entries=listOf(setOf("app:a"),setOf("file-stat:exact","shared"),setOf("app:c"))),rows,emptyMap())
        assertNull(ambiguous.currentId)
        assertEquals(ResumeAvailability.AMBIGUOUS,ambiguous.availability[1])
    }
    @Test fun conflictingIdentityIsNeverAReplacementAndReasonSurvivesRebind(){
        val saved=session().copy(entries=listOf(setOf("app:a"),setOf("old:b","file-stat:exact"),setOf("app:c")))
        val rows=listOf(c("a","app:a"),c("b","file-stat:exact"),c("c","app:c"))
        val resolved=ResumeQueuePolicy.resolve(saved,rows,mapOf("old:b" to "history-1","file-stat:exact" to "history-2"))
        assertNull(resolved.currentId)
        assertEquals(ResumeAvailability.CONFLICTING,resolved.availability[1])
        assertEquals(resolved.availability,ResumeQueuePolicy.rebindResolved(listOf(resolved),listOf(saved.copy(positionMs=2000))).single().availability)
    }
    @Test fun queueLimitAndContextAreStable(){
        val entries=session().entries
        assertEquals(ResumeQueuePolicy.contextId(null,entries),ResumeQueuePolicy.contextId(null,entries))
        assertNotEquals(ResumeQueuePolicy.contextId(null,entries),ResumeQueuePolicy.contextId(null,entries.reversed()))
        assertEquals("playlist:p",ResumeQueuePolicy.contextId("p",entries))
        assertNull(ResumeQueuePolicy.contextId(null,listOf(emptySet())))
        var all=emptyList<ResumeQueueSession>()
        for(i in 1..12)all=ResumeQueuePolicy.keep(all,session("$i"))
        assertEquals(10,all.size);assertEquals("12",all.first().id)
        all=ResumeQueuePolicy.keep(all,session("10"));assertEquals(10,all.size);assertEquals("10",all.first().id)
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
    @Test fun membershipIgnoresSessionOrderAndPlaybackPositionButNotQueueChanges(){
        val a=session("a");val b=session("b")
        assertEquals(ResumeQueuePolicy.membership(listOf(a,b)),ResumeQueuePolicy.membership(listOf(b.copy(positionMs=8000),a)))
        assertNotEquals(ResumeQueuePolicy.membership(listOf(a,b)),ResumeQueuePolicy.membership(listOf(a.copy(entries=a.entries.reversed()),b)))
        assertNotEquals(ResumeQueuePolicy.membership(listOf(a,b)),ResumeQueuePolicy.membership(listOf(a.copy(occurrenceIds=a.occurrenceIds.reversed()),b)))
    }
    @Test fun rebindPreservesOnlyUnchangedQueuesAndTheirLatestPosition(){
        val a=session("a");val b=session("b");val c=session("c")
        val saved=listOf(a,b,c).map{ResolvedResumeQueue(it,listOf("a","b","c"))}
        val recent=b.copy(positionMs=9000)
        val edited=a.copy(entries=a.entries.reversed())
        val result=ResumeQueuePolicy.rebindResolved(saved,listOf(recent,edited,session("new")))
        assertEquals(listOf(ResolvedResumeQueue(recent,listOf("a","b","c"))),result)
        assertTrue(ResumeQueuePolicy.rebindResolved(saved,emptyList()).isEmpty())
    }
}
