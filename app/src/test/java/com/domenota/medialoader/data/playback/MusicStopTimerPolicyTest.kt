package com.domenota.medialoader.data.playback
import org.junit.Assert.*
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
  assertFalse(MusicStopTimerPolicy.valid(MusicStopRequest(MusicStopCriterion.TRACKS,1000)))
  assertTrue(MusicStopTimerPolicy.valid(MusicStopRequest(MusicStopCriterion.TRACKS,50)))
 }
}
