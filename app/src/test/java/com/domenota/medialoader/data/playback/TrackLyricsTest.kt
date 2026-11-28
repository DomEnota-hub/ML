package com.domenota.medialoader.data.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class TrackLyricsTest {
    @Test fun lrcSortsRepeatedStampsAndUsesOffset() {
        val lyrics=LyricsParser.parse("[ar:Example]\n[offset:+250]\n[00:03.1][00:01.02]first\n[00:02]second",LyricsOrigin.IMPORTED)!!
        assertEquals(listOf(1270L,2250L,3350L),lyrics.timed.map{it.atMs})
        assertEquals(-1,lyrics.active(1269))
        assertEquals(0,lyrics.active(1270))
        assertEquals(1,lyrics.active(2000,250))
        assertEquals(2000L,lyrics.seek(1,250,4000))
        assertNull(lyrics.seek(2,0,3000))
    }
    @Test fun plainAndInvalidInputAreDistinct() {
        assertEquals("Invented test words",LyricsParser.parse("Invented test words",LyricsOrigin.IMPORTED)?.plain)
        assertTrue(LyricsParser.parse("Invented test words",LyricsOrigin.IMPORTED)!!.timed.isEmpty())
        assertNull(LyricsParser.parse("[ti:Metadata]",LyricsOrigin.IMPORTED))
        assertNull(LyricsParser.parse("x".repeat(LyricsParser.MAX_TEXT+1),LyricsOrigin.IMPORTED))
        assertNull(LyricsParser.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(),0x28))))
    }
    @Test fun readsUnsynchronizedId3WithoutAudioDecode() {
        val payload=byteArrayOf(3,101,110,103,0)+"Invented test words".toByteArray()
        val frame="USLT".toByteArray()+byteArrayOf(0,0,0,payload.size.toByte(),0,0)+payload
        val tag=byteArrayOf(73,68,51,3,0,0,0,0,0,frame.size.toByte())+frame
        assertEquals("Invented test words",EmbeddedLyrics.read(ByteArrayInputStream(tag))?.plain)
        assertNull(EmbeddedLyrics.read(ByteArrayInputStream(tag.copyOf(12))))
    }
    @Test fun readsMillisecondSyltAndRejectsFrameTimestamps() {
        val payload=byteArrayOf(3,101,110,103,2,1,0)+"first".toByteArray()+byteArrayOf(0,0,0,3,-24)+
            "second".toByteArray()+byteArrayOf(0,0,0,7,-48)
        fun tag(body:ByteArray):ByteArray {
            val frame="SYLT".toByteArray()+byteArrayOf(0,0,0,body.size.toByte(),0,0)+body
            return byteArrayOf(73,68,51,3,0,0,0,0,0,frame.size.toByte())+frame
        }
        val result=EmbeddedLyrics.read(ByteArrayInputStream(tag(payload)))!!
        assertEquals(listOf(1000L,2000L),result.timed.map{it.atMs})
        val unsupported=payload.copyOf().apply{this[4]=1}
        assertNull(EmbeddedLyrics.read(ByteArrayInputStream(tag(unsupported))))
    }
    @Test fun requiresUniqueExactMetadataAndDuration() {
        val s=LyricsTrackSignature("Title","Artist","Album",120000)
        val good=LyricsCandidate(1,s.copy(title=" title ",durationMs=121999))
        assertEquals(good,LyricsMatch.exact(s,listOf(good)))
        assertNull(LyricsMatch.exact(s,listOf(good,good.copy(id=2))))
        assertNull(LyricsMatch.exact(s,listOf(good,good.copy(signature=s.copy(title="Different")))))
        assertNull(LyricsMatch.exact(s,listOf(good.copy(signature=s.copy(title="Title Live")))))
        assertNull(LyricsMatch.exact(s,listOf(good.copy(signature=s.copy(durationMs=122001)))))
        assertNull(LyricsMatch.exact(s.copy(album=null),listOf(good)))
    }
}
