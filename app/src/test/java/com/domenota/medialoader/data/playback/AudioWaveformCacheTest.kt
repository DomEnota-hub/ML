package com.domenota.medialoader.data.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AudioWaveformCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cachedTrackDoesNotWaitForAnotherTracksAnalysis() {
        val folder = temporary.newFolder()
        val expected = floatArrayOf(0.2f, 0.7f)
        AudioWaveformCache(folder).get("ready") { expected }
        val cache = AudioWaveformCache(folder)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            executor.submit {
                cache.get("slow") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    expected
                }
            }
            check(entered.await(2, TimeUnit.SECONDS))
            val ready = executor.submit<FloatArray> {
                cache.get("ready") { error("Saved waveform must not be decoded again") }
            }
            assertArrayEquals(expected, ready.get(1, TimeUnit.SECONDS), 0f)
            assertArrayEquals(expected, cache.peek("ready"), 0f)
            assertArrayEquals(expected, cache.get("ready") { error("Must reuse memory") }, 0f)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
