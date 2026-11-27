package com.domenota.medialoader.data.playback
import com.domenota.medialoader.data.tasks.*
import org.junit.Assert.*
import org.junit.Test
class WaveformSweepTest {
    @Test fun checkpointDetailsAreActualDeltasAndBounded(){
        var s=sweep(25)
        repeat(25){index->
            s=s.advance(if(index%2==0)WaveformOutcome.CACHED else WaveformOutcome.GENERATED).finishCheckpoint(index.toLong()+10)
        }
        val summary=s.details().checkpoints!!
        assertEquals(25,summary.count)
        assertEquals(20,summary.recent.size)
        assertEquals(6,summary.recent.first().ordinal)
        assertEquals(25,summary.recent.last().ordinal)
        assertEquals(1,summary.recent.last().outcomes.cached)
        assertEquals(s,s.finishCheckpoint(100))
    }
    private fun sweep(total:Int)=WaveformSweep(TaskCorrelation.create(),"inventory",total,10,TaskTrigger.BACKGROUND)
    @Test fun batchesShareOneOperationAndCountRealOutcomes(){
        var s=sweep(25)
        val token=s.token
        repeat(20){s=s.advance(WaveformOutcome.CACHED)}
        assertFalse(s.finished)
        repeat(3){s=s.advance(WaveformOutcome.GENERATED)}
        s=s.advance(WaveformOutcome.SKIPPED).advance(WaveformOutcome.FAILED)
        assertTrue(s.finished);assertEquals(token,s.details().correlation)
        assertEquals(TaskOutcomes(20,3,1,1),s.outcomes)
        assertEquals(25,s.cursor)
    }
    @Test fun inventoryFingerprintIsOrderedAndUnambiguous(){
        assertNotEquals(WaveformSweep.fingerprint(listOf("ab","c")),WaveformSweep.fingerprint(listOf("a","bc")))
        assertNotEquals(WaveformSweep.fingerprint(listOf("a","b")),WaveformSweep.fingerprint(listOf("b","a")))
        assertEquals(WaveformSweep.fingerprint(listOf("a","b")),WaveformSweep.fingerprint(listOf("a","b")))
    }
    @Test fun failureReasonsRemainTypedAndMixedFailuresAreUnknown(){
        val first=sweep(3).advance(WaveformOutcome.FAILED,TaskFailure.CACHE_WRITE)
        assertEquals(TaskFailure.CACHE_WRITE,first.details().failure)
        assertTrue(first.details(recovered=true).recovered)
        assertEquals(TaskFailure.UNKNOWN,first.advance(WaveformOutcome.FAILED,TaskFailure.DECODE).details().failure)
    }
    @Test fun completedSweepCannotAdvance(){
        assertTrue(runCatching{sweep(0).advance(WaveformOutcome.CACHED)}.isFailure)
    }
}
