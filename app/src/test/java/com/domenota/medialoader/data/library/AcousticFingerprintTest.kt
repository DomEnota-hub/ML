package com.domenota.medialoader.data.library

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import java.util.Random

class AcousticFingerprintTest {
    private fun audio(seed: Long): FloatArray {
        val random = Random(seed)
        val notes = DoubleArray(80) { 180+random.nextDouble()*1600 }
        return FloatArray(8000*32) { i ->
            val t = i/8000.0; val k = (t*2).toInt(); val f = notes[k]
            ((sin(2*PI*f*t)+.4*sin(2*PI*f*1.499*t)+.2*sin(2*PI*notes[(k+9)%80]*t))*.3).toFloat()
        }
    }
    private fun fingerprint(samples: FloatArray) = AcousticFingerprints.Builder().also { builder -> samples.forEach(builder::accept) }.finish()
    @Test fun arbitraryLeadingAndTrailingTrimMatchesWithoutMetadata() {
        val source = audio(17)
        assertTrue(AcousticFingerprints.matches(fingerprint(source),fingerprint(source.copyOfRange(25384,206184))))
    }
    @Test fun differentRecordingAndSilenceAreRejected() {
        assertFalse(AcousticFingerprints.matches(fingerprint(audio(17)),fingerprint(audio(83))))
        assertFalse(AcousticFingerprints.matches(fingerprint(FloatArray(80000)),fingerprint(FloatArray(80000))))
    }
    @Test fun boundedAndCancellable() {
        val samples = audio(17)
        assertTrue(fingerprint(samples).landmarks.size <= 6000)
        val builder = AcousticFingerprints.Builder { throw java.util.concurrent.CancellationException() }
        try { samples.forEach(builder::accept); fail("cancellation required") } catch (_: java.util.concurrent.CancellationException) { }
    }
    @Test fun indexRetrievesIdenticalAudioAndBulkDoesNotSelectFragmentMatches() {
        val fp = fingerprint(audio(17))
        assertTrue(("a" to "b") in AcousticFingerprints.candidatePairs(mapOf("a" to fp,"b" to fp)))
        val a = DuplicateTrack("a","One",null,"a.wav","uri:a",1,1,"a",null,32000,null,null,acoustic=fp)
        val b = a.copy(id="b",title="Two",sha="b",uri="uri:b")
        val group = MusicDuplicatePolicy.groups(listOf(a,b)).single()
        assertEquals(DuplicateKind.ACOUSTIC,group.kind)
        assertTrue(MusicDuplicatePolicy.selectAll(listOf(group),emptyMap()).isEmpty())
        assertFalse(MusicDuplicatePolicy.validSelection(listOf(group),setOf("a","b")))
        assertTrue(MusicDuplicatePolicy.validSelection(listOf(group),setOf("b")))
        val exactCopy = a.copy(id="c",uri="uri:c")
        val combined = MusicDuplicatePolicy.groups(listOf(a,b,exactCopy)).single()
        assertEquals(3,combined.tracks.size)
        assertEquals(DuplicateKind.ACOUSTIC,combined.kind)
        assertTrue(MusicDuplicatePolicy.selectAll(listOf(combined),emptyMap()).isEmpty())
    }
}
