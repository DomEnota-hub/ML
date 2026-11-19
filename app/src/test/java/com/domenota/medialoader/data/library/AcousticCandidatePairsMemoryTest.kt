package com.domenota.medialoader.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Candidate admission preserves exact votes while bounded tiles prevent OOM. */
class AcousticCandidatePairsMemoryTest {
    @Test fun thresholdStillRequires90DistinctHashes() {
        val common=(1..90).map { AcousticLandmark(it,it*4) }
        val input=linkedMapOf(
            "a" to AcousticFingerprint(common),
            "b" to AcousticFingerprint(common),
            "c" to AcousticFingerprint(common.dropLast(1)),
        )
        assertEquals(setOf("a" to "b"),AcousticFingerprints.candidatePairs(input))
    }

    @Test fun thousandsOfSparseTracksDoNotAllocateGlobalPairsMap() {
        val count=3200
        val values=Array(count) { ArrayList<AcousticLandmark>() }
        // 1.17m raw pair-votes with insufficient shared hashes:
        // previously every pair was held in a global boxed HashMap.
        for(hash in 1..1500) {
            val base=(hash*113)%count
            for(slot in 0 until 40) {
                val track=(base+slot*17)%count
                values[track].add(AcousticLandmark(hash,hash))
            }
        }
        val input=(0 until count).associate { i ->
            "%04d".format(i) to AcousticFingerprint(values[i])
        }
        var checkpoints=0
        val result=AcousticFingerprints.candidatePairs(input) { checkpoints++ }
        assertTrue(result.isEmpty())
        assertTrue(checkpoints>count)
    }
}
