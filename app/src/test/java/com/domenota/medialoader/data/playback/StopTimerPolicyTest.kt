package com.domenota.medialoader.data.playback
import androidx.media3.common.Player
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test
class StopTimerPolicyTest {
    @Test fun nextLocalTimeWrapsToTomorrow() {
        val now = ZonedDateTime.parse("2026-11-13T23:45:00+03:00")
        assertEquals(now.plusMinutes(30).toInstant().toEpochMilli(),StopTimerPolicy.nextTime(now,0,15))
    }
    @Test fun onlyNaturalCompletionCounts() {
        assertEquals(2L,StopTimerPolicy.countAfterTransition(3,Player.MEDIA_ITEM_TRANSITION_REASON_AUTO))
        assertEquals(2L,StopTimerPolicy.countAfterTransition(3,Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT))
        assertEquals(3L,StopTimerPolicy.countAfterTransition(3,Player.MEDIA_ITEM_TRANSITION_REASON_SEEK))
        assertEquals(3L,StopTimerPolicy.countAfterTransition(3,Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED))
    }
}
