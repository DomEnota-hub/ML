package com.domenota.medialoader.data.playback
import com.domenota.medialoader.data.tasks.*
import org.junit.Assert.*
import org.junit.Test
class WaveformSweepTest {
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
    @Test fun completedSweepCannotAdvance(){
        assertTrue(runCatching{sweep(0).advance(WaveformOutcome.CACHED)}.isFailure)
    }
}
