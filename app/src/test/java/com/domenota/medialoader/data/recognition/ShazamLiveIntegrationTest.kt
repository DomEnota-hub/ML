package com.domenota.medialoader.data.recognition

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in live smoke against the same audio fixture used by upstream ShazamIO.
 * It is skipped in normal test runs and enabled only by a temporary CI environment variable.
 */
class ShazamLiveIntegrationTest {
    @Test fun recognizesUpstreamShazamIoFixtureWhenEnabled() = runBlocking {
        val path = System.getenv("SHAZAM_LIVE_PCM")
        assumeTrue("Live Shazam fixture is not enabled", !path.isNullOrBlank())

        val bytes = File(path!!).readBytes()
        val usable = bytes.size - bytes.size % 2
        val samples = ShortArray(usable / 2)
        ByteBuffer.wrap(bytes, 0, usable)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)

        val result = ShazamRecognitionProvider().recognize(samples)

        assertEquals("53982678", result?.trackId)
    }
}
