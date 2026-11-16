package com.domenota.medialoader.data.library

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class EncodedAudioPayloadTest {
    private val prefix = "audio/mpeg\u0000sample-rate=44100channel-count=2\u0000csd".toByteArray()
    private fun digest() = MessageDigest.getInstance("SHA-256").apply { update(prefix) }
    private fun heapReference(samples: List<ByteArray>): String? {
        val digest = digest()
        val buffer = ByteBuffer.allocate(1024 * 1024)
        for (sample in samples) {
            buffer.clear(); buffer.put(sample)
            buffer.position(0); buffer.limit(sample.size); digest.update(buffer)
        }
        return if (samples.isEmpty()) null else digest.digest().joinToString("") { "%02x".format(it) }
    }
    @Test fun directTransportMatchesOriginalHeapDigestForEverySampleByteAndFormatPrefix() {
        val samples = listOf(ByteArray(40_000) { (it * 17).toByte() }, byteArrayOf(),
            ByteArray(900) { (255 - it).toByte() }, byteArrayOf(0, 1, -1))
        var index = 0
        var activeChecks = 0
        val result = EncodedAudioPayload.digestSamples(digest(), null, checkActive = { activeChecks++ }, read = {
            assertTrue(it.isDirect)
            assertEquals(1024 * 1024, it.capacity())
            assertEquals(0, it.position())
            assertEquals(it.capacity(), it.limit())
            it.put(samples[index]); samples[index].size
        }, advance = { ++index < samples.size })
        assertEquals(heapReference(samples), result)
        assertEquals(samples.size, activeChecks)
        val changed = samples.toMutableList().apply { set(3, byteArrayOf(0, 1, -2)) }
        assertNotEquals(heapReference(changed), result)
    }
    @Test fun bufferBoundsAndEmptyOrOversizeSamplesPreserveFailureBehavior() {
        for ((hint, expected) in listOf(-1 to 65_536, 8192 to 65_536, Int.MAX_VALUE to 4_194_304)) {
            assertNull(EncodedAudioPayload.digestSamples(digest(), hint, checkActive = {}, read = {
                assertTrue(it.isDirect); assertEquals(expected, it.capacity()); -1
            }, advance = { error("no sample") }))
        }
        assertNull(EncodedAudioPayload.digestSamples(digest(), null, checkActive = {},
            read = { it.capacity() + 1 }, advance = { error("oversize sample") }))
    }
    @Test fun cancellationStillStopsBeforeReadingNextEncodedSample() {
        var reads = 0
        var checks = 0
        try {
            EncodedAudioPayload.digestSamples(digest(), null, checkActive = {
                if (++checks == 2) throw CancellationException("synthetic")
            }, read = { reads++; it.put(1.toByte()); 1 }, advance = { true })
            fail("must cancel")
        } catch (_: CancellationException) { }
        assertEquals(1, reads)
    }
}
