package com.domenota.medialoader.core.logging

import org.junit.Assert.*
import org.junit.Test

class LibraryProjectionCountersTest {
    @Test fun boundedCountersDistinguishReasonCompletionCancellationAndReuse() {
        var now=0L
        val set=LibraryProjectionCounterSet{now}
        val first=set.start(LibraryProjectionKind.AUDIO_SESSION,LibraryProjectionReason.INITIAL)
        now=23_000_000;first.complete();first.close()
        val second=set.start(LibraryProjectionKind.AUDIO_SESSION,LibraryProjectionReason.INPUT)
        now=30_000_000;second.close();second.close()
        val third=set.start(LibraryProjectionKind.AUDIO_SESSION,LibraryProjectionReason.ACCESS)
        now=72_000_000;third.complete();third.complete()
        repeat(8){set.reuse(LibraryProjectionKind.AUDIO_SESSION)}
        assertTrue(set.summary().contains("AUDIO_SESSION[initial=1,input=1,access=1,done=2,aborted=1,reuse=8,totalMs=65,maxMs=42]"))
        assertTrue(set.summary().contains("PUBLIC_ROWS[initial=0,input=0,access=0,done=0,aborted=0,reuse=0,totalMs=0,maxMs=0]"))
    }
}
