package com.domenota.medialoader.core.logging

import org.junit.Assert.*
import org.junit.Test

class ReadModelGuardWindowTest{
    @Test fun repeatedObservationsAreBoundedAndDrainedWithoutLosingFirstOrCount(){
        var now=100L
        val window=ReadModelGuardWindow({now},1000)
        val kind=LibraryProjectionKind.OTHER
        val reason=ReadModelGuardReason.ACCESS
        assertEquals(1,window.record(kind,reason).size)
        repeat(250){now++;assertTrue(window.record(kind,reason).isEmpty())}
        val result=window.drain()
        assertEquals(1,result.size)
        assertTrue(result.single().contains("repeatedSuppressed=250"))
        assertTrue(result.single().contains("windowMs=250"))
        assertTrue(window.drain().isEmpty())
        assertEquals(1,window.record(kind,reason).size)
    }
    @Test fun rolloverAndDifferentReasonsRemainDistinctAndClearDropsPendingRepeats(){
        var now=0L
        val window=ReadModelGuardWindow({now},100)
        val kind=LibraryProjectionKind.AUDIO_SESSION
        assertEquals(1,window.record(kind,ReadModelGuardReason.DISABLED).size)
        now=10
        assertTrue(window.record(kind,ReadModelGuardReason.DISABLED).isEmpty())
        assertEquals(1,window.record(kind,ReadModelGuardReason.PRIVACY).size)
        now=200
        val rolled=window.record(kind,ReadModelGuardReason.DISABLED)
        assertEquals(2,rolled.size)
        assertTrue(rolled.first().contains("repeatedSuppressed=1"))
        assertFalse(rolled.last().contains("repeatedSuppressed"))
        now=201
        window.record(kind,ReadModelGuardReason.DISABLED)
        window.clear()
        assertTrue(window.drain().isEmpty())
    }
}
