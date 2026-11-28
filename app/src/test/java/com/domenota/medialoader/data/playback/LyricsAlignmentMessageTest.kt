package com.domenota.medialoader.data.playback

import com.domenota.medialoader.data.playback.alignment.LyricsAlignmentCoordinator
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class LyricsAlignmentMessageTest {
    @Test fun successRequiresRereadTimedLyricsForTheSameOriginalText() {
        val hash=MessageDigest.getInstance("SHA-256").digest("Original".toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
        val state=LyricsAlignmentCoordinator.State(message="Текст синхронизирован",completedTextHash=hash)
        val timed=TrackLyrics("Original",listOf(LyricLine(100,"Original")),LyricsOrigin.IMPORTED)
        assertEquals(state.message,LyricsAlignmentCoordinator.messageFor(state,timed))
        assertNull(LyricsAlignmentCoordinator.messageFor(state,timed.copy(timed=emptyList())))
        assertNull(LyricsAlignmentCoordinator.messageFor(state,timed.copy(plain="Replacement")))
        assertNull(LyricsAlignmentCoordinator.messageFor(state,null))
    }
}
