package com.domenota.medialoader.data.recognition

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MicrophoneCaptureTest {
    @Test fun shortReadsFillExactWindowAndReportWholeSeconds() = runBlocking {
        val progress = mutableListOf<Int>()
        var value = 0
        val pcm = collectMicrophonePcm(targetSamples = 10, sampleRate = 5,
            read = { output, offset, requested ->
                val count = minOf(2, requested)
                repeat(count) { output[offset + it] = (++value).toShort() }
                count
            }, onProgress = { progress += it })
        assertArrayEquals(ShortArray(10) { (it + 1).toShort() }, pcm)
        assertEquals(listOf(1, 2), progress)
    }

    @Test(expected = IOException::class)
    fun failedReadDoesNotReturnPartialAudio() {
        runBlocking {
            collectMicrophonePcm(targetSamples = 10, sampleRate = 5,
                read = { _, _, _ -> -1 }, onProgress = {})
        }
    }

    @Test fun earlyFinishReturnsOnlyCapturedSamplesAndReportsVolume() = runBlocking {
        var finish = false
        val levels = mutableListOf<Float>()
        val pcm = collectMicrophonePcm(targetSamples = 20, sampleRate = 5,
            minSamples = 5, shouldFinish = { finish },
            read = { output, offset, count ->
                val amount = minOf(2, count)
                repeat(amount) { output[offset + it] = 16_000 }
                amount
            }, onProgress = { if (it >= 1) finish = true }, onLevel = { levels += it })
        assertEquals(6, pcm.size)
        assertTrue(levels.any { it > 0.5f })
    }

    @Test fun ordinaryVoiceAmplitudeProducesVisibleLevelButSilenceDoesNot() = runBlocking {
        val levels = mutableListOf<Float>()
        var reads = 0
        collectMicrophonePcm(targetSamples = 20, sampleRate = 10,
            read = { output, offset, count ->
                val amount = minOf(10, count)
                repeat(amount) { output[offset + it] = if (reads == 0) 400 else 0 }
                reads++
                amount
            }, onProgress = {}, onLevel = { levels += it })
        assertTrue("A normal voice should move the wave", levels.first() > 0.25f)
        assertEquals(0f, levels.last())
    }
}
