package com.domenota.medialoader.data.playback

import com.domenota.medialoader.data.tasks.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LyricsBatchPlanTest {
    private val keys=listOf("a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64))
    @Test fun checkpointRoundtripRetainsOnlyVersionHashesAndRetryFailures() {
        val plan=LyricsBatchPlan.fresh(keys).advance(LyricsBatchOutcome.CACHED)
            .advance(LyricsBatchOutcome.FAILED).advance(LyricsBatchOutcome.DOWNLOADED)
        val restored=LyricsBatchCodec.decode(LyricsBatchCodec.encode(plan))
        assertEquals(plan,restored)
        assertEquals(3,restored.cursor)
        assertEquals(listOf(keys[1]),restored.failed)
        assertEquals(TaskStatus.PAUSED,restored.status)
        assertEquals(TaskOutcomes(1,1,0,1),restored.outcomes)
        assertEquals(keys[3],restored.keys[restored.cursor])
    }
    @Test fun completedWithMissingTextIsPartialNotFailure() {
        val plan=LyricsBatchPlan.fresh(keys.take(2)).advance(LyricsBatchOutcome.CACHED)
            .advance(LyricsBatchOutcome.SKIPPED).finish()
        assertEquals(TaskStatus.PARTIAL,plan.status)
        assertEquals(0,plan.outcomes.failed)
        assertTrue(plan.failed.isEmpty())
        assertEquals(TaskStatus.COMPLETE,LyricsBatchPlan.fresh(keys.take(1)).advance(LyricsBatchOutcome.DOWNLOADED).finish().status)
    }
    @Test fun invalidCheckpointRejectsRawAddressesAndUnprovenResults() {
        assertTrue(runCatching{LyricsBatchPlan.fresh(listOf("content://media/audio/123"))}.isFailure)
        val valid=LyricsBatchPlan.fresh(keys)
        assertTrue(runCatching{valid.copy(cursor=1)}.isFailure)
        assertTrue(runCatching{valid.copy(status=TaskStatus.COMPLETE)}.isFailure)
        assertTrue(runCatching{valid.copy(keys=listOf(keys[0],keys[0]))}.isFailure)
    }
    @Test fun taskCenterUsesMusicSectionAndTypedOwner() {
        assertTrue(TaskKind.LYRICS in TaskSection.MUSIC.kinds)
        assertEquals(TaskOwner.LYRICS,TaskCenterPresentation.owner(TaskKind.LYRICS))
        val row=TaskSnapshot(taskId(TaskOwner.LYRICS,"test"),TaskKind.LYRICS,TaskStatus.RUNNING,files=TaskProgress(2,4))
        assertEquals("2 из 4 треков",TaskCenterPresentation.progressText(row))
    }
}
