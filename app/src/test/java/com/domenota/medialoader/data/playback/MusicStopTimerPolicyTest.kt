package com.domenota.medialoader.data.playback
import org.junit.Assert.*
import androidx.media3.common.Player
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
class MusicStopTimerPolicyTest {
 @Test fun nextLocalTimeUsesTomorrowWhenAlreadyPassed() {
  val now=Instant.parse("2026-11-13T20:00:00Z").toEpochMilli()
  assertEquals(Instant.parse("2026-11-14T19:30:00Z").toEpochMilli(),MusicStopTimerPolicy.nextTime(now,22*60+30,ZoneId.of("Europe/Moscow")))
 }
 @Test fun invalidRequestsDoNotArm() {
  assertFalse(MusicStopTimerPolicy.valid(MusicStopRequest(MusicStopCriterion.AFTER_TIME,0)))
  assertEquals(5L,MusicStopTimerPolicy.durationSeconds("","","05"))
  assertEquals(0L,MusicStopTimerPolicy.durationSeconds("","",""))
  assertEquals(-1L,MusicStopTimerPolicy.durationSeconds("","60",""))
  assertEquals(-1L,MusicStopTimerPolicy.durationSeconds("","","60"))
  assertEquals(-1L,MusicStopTimerPolicy.durationSeconds("24","",""))
  assertEquals(3600L,MusicStopTimerPolicy.durationSeconds("1","",""))
  assertTrue(MusicStopTimerPolicy.valid(MusicStopRequest(MusicStopCriterion.AFTER_TIME,5)))
  assertEquals(listOf(MusicStopCriterion.AT_TIME,MusicStopCriterion.AFTER_TIME,MusicStopCriterion.PLAYLIST),MusicStopCriterion.entries.toList())
  assertTrue(MusicStopTimerPolicy.playlistAvailable(true,"playlist",Player.REPEAT_MODE_ALL))
  assertTrue(MusicStopTimerPolicy.playlistAvailable(true,"playlist",Player.REPEAT_MODE_OFF))
  assertFalse(MusicStopTimerPolicy.playlistAvailable(true,"playlist",Player.REPEAT_MODE_ONE))
  assertFalse(MusicStopTimerPolicy.playlistAvailable(true,null,Player.REPEAT_MODE_OFF))
  assertFalse(MusicStopTimerPolicy.playlistAvailable(false,"playlist",Player.REPEAT_MODE_OFF))
 }
}
