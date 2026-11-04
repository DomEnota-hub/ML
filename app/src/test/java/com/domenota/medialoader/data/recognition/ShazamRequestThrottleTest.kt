package com.domenota.medialoader.data.recognition

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShazamRequestThrottleTest {
    @Test fun parsesNumericRetryAfter() {
        assertEquals(45_000L, parseShazamRetryAfterMs("45", 0L))
        assertEquals(0L, parseShazamRetryAfterMs("0", 0L))
        assertEquals(86_400_000L, parseShazamRetryAfterMs("999999999", 0L))
    }

    @Test fun parsesHttpDateRetryAfter() {
        val now = Instant.parse("2026-11-04T20:00:00Z").toEpochMilli()
        assertEquals(3_000L, parseShazamRetryAfterMs("Wed, 04 Nov 2026 20:00:03 GMT", now))
        assertEquals(0L, parseShazamRetryAfterMs("Wed, 04 Nov 2026 19:59:00 GMT", now))
    }

    @Test fun rejectsInvalidRetryAfter() {
        assertEquals(null, parseShazamRetryAfterMs(null, 0L))
        assertEquals(null, parseShazamRetryAfterMs("-100", 0L))
        assertEquals(null, parseShazamRetryAfterMs("not-a-header", 0L))
    }

    @Test fun rateLimitPreventsNewTrafficUntilCooldownEnds() = runBlocking {
        var timeMs = 1_000_000L
        val throttle = ShazamRequestThrottle(nowMs = { timeMs }, minSpacingMs = 0L)
        var networkCalls = 0
        try {
            throttle.execute<Int> {
                networkCalls++
                throw ShazamRateLimitException(120_000L)
            }
            fail("429 must surface")
        } catch (e: ShazamRateLimitException) {
            assertEquals(120_000L, e.retryInMs)
        }
        try {
            throttle.execute { networkCalls++; 7 }
            fail("Cooldown must reject the request without contacting Shazam")
        } catch (e: ShazamRateLimitException) {
            assertEquals(120_000L, e.retryInMs)
        }
        assertEquals(1, networkCalls)
        timeMs += 119_999L
        try {
            throttle.execute { networkCalls++; 7 }
            fail("The last millisecond must still be blocked")
        } catch (e: ShazamRateLimitException) {
            assertEquals(1L, e.retryInMs)
        }
        timeMs += 1L
        assertEquals(7, throttle.execute { networkCalls++; 7 })
        assertEquals(2, networkCalls)
    }

    @Test fun tooShortServerRetryAfterUsesSafeFloor() = runBlocking {
        val throttle = ShazamRequestThrottle(nowMs = { 1_000_000L }, minSpacingMs = 0L)
        try {
            throttle.execute<Unit> { throw ShazamRateLimitException(3_000L) }
            fail("Rate limit must propagate")
        } catch (e: ShazamRateLimitException) {
            assertEquals(60_000L, e.retryInMs)
            assertTrue(e.message.orEmpty().contains("1 мин"))
        }
    }

    @Test fun onlyTransientTransportFailuresMayBeRetried() {
        assertTrue(isTransientShazamNetworkError(SocketTimeoutException("Read timed out")))
        assertTrue(isTransientShazamNetworkError(EOFException("EOF")))
        assertTrue(isTransientShazamNetworkError(IOException("unexpected end of stream")))
        assertFalse(isTransientShazamNetworkError(IOException("HTTP 403")))
        assertFalse(isTransientShazamNetworkError(ShazamRateLimitException(60_000L)))
    }
}
