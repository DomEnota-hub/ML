package com.domenota.medialoader.core.logging
import com.domenota.medialoader.data.tasks.*
import org.junit.Assert.*
import org.junit.Test
class TaskJournalDisplayTest {
    @Test fun relatedFilterMatchesExactTypedTokenAndTag(){
        val a=TaskCorrelation.create()
        val b=TaskCorrelation.create()
        fun line(tag:String,token:TaskCorrelation)="2026-11-27 01:00:00 [INFO] [$tag] COMPLETE · op=${token.value} · kind=WAVEFORM · status=COMPLETE"
        val raw=listOf(line("TaskCenter",a),line("TaskCenter",b),line("Other",a)).joinToString("\n")
        assertEquals(line("TaskCenter",a),TaskJournalDisplay.related(raw,a))
    }
    @Test fun readableTaskEventsNeverEchoUnknownFieldsOrCorrelation(){
        val token=TaskCorrelation.create()
        val raw="2026-11-27 01:00:00 [INFO] [TaskCenter] [thread=DefaultDispatcher-worker-1] COMPLETE · op=${token.value} · kind=WAVEFORM · status=COMPLETE · generated=2 · cached=4 · raw=private/path"
        val text=LogDisplay.readable(LogDisplay.entries(raw).single())
        assertTrue(text.contains("Создано: 2"));assertTrue(text.contains("В кэше: 4"))
        assertFalse(text.contains(token.value));assertFalse(text.contains("private/path"))
    }
}
