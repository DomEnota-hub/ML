package com.domenota.medialoader.data.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun diskCacheIsReadableAfterProcessRestartWithoutAudioDecode() {
        val folder = temporary.newFolder()
        val source = "content://media/external/audio/media/901"
        val expected = floatArrayOf(.15f, .42f, .9f, .05f)
        val first = AudioWaveformCache(folder)
        assertFalse(first.has(source))
        first.get(source) { expected }
        val restarted = AudioWaveformCache(folder)
        assertTrue(restarted.has(source))
        assertArrayEquals(expected, restarted.peek(source), 0f)
        assertArrayEquals(expected,
            restarted.get(source) { error("Disk cache must prevent decoder restart") }, 0f)
        assertFalse(restarted.has("content://media/external/audio/media/902"))
    }

    @Test fun truncatedDiskWaveformTriggersFreshDecode() {
        val folder = temporary.newFolder()
        val source = "saved-track"
        AudioWaveformCache(folder).get(source) { floatArrayOf(.2f, .4f) }
        val saved = folder.listFiles()!!.single()
        saved.writeBytes(byteArrayOf(0, 0, 0, 99))
        val fresh = AudioWaveformCache(folder)
        assertFalse(fresh.has(source))
        assertArrayEquals(floatArrayOf(.8f),
            fresh.get(source) { floatArrayOf(.8f) }, 0f)
    }

    @Test fun replacingAudioAtSameUriInvalidatesMemoryAndDiskWaveform() {
        val folder = temporary.newFolder()
        val uri = "content://media/external/audio/media/177"
        val cache = AudioWaveformCache(folder)
        cache.get(uri) { floatArrayOf(.2f, .3f) }
        assertTrue(cache.has(uri))
        cache.invalidate(uri)
        assertFalse(cache.has(uri))
        val reopened = AudioWaveformCache(folder)
        assertFalse(reopened.has(uri))
        assertArrayEquals(floatArrayOf(.85f, .95f),
            reopened.get(uri) { floatArrayOf(.85f, .95f) }, 0f)
    }


}
