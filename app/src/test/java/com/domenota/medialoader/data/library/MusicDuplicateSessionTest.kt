package com.domenota.medialoader.data.library

import org.junit.Assert.*
import org.junit.Test

class MusicDuplicateSessionTest {
    @Test fun snapshotRoundTrip() {
        val a = DuplicateTrack("a","Song",null,"a.mp3","content://media/audio/1",123,456,"hash",null,1000,null,null)
        val b = a.copy(id="b",filename="b.mp3",uri="content://media/audio/2")
        val group = DuplicateGroup(listOf(a,b),"Song","Same")
        val snapshot = DuplicateSession(DuplicateScanResult(listOf(group),2),setOf("b"),mapOf(group.key to "a"),3,45,123456)
        assertEquals(snapshot, MusicDuplicateSessionStore.decode(MusicDuplicateSessionStore.encode(snapshot)))
    }
    @Test fun invalidSchemaRejected() {
        try { MusicDuplicateSessionStore.decode("{\"schema\":2}"); fail("schema") } catch (_: IllegalArgumentException) {}
    }
}
