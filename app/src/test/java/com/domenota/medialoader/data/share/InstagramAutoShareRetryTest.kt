package com.domenota.medialoader.data.share

import com.domenota.medialoader.core.provider.ProviderException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class InstagramAutoShareRetryTest {
    @Test fun dnsFailureSurvivesUntilTheNextRunCanResolve() = runBlocking {
        val first = resolveAutomaticShare(0) { throw UnknownHostException("www.instagram.com") }
        assertTrue(first is AutomaticShareResolution.Retry)
        val next = resolveAutomaticShare(1) { listOf("photo") }
        assertEquals(listOf("photo"), (next as AutomaticShareResolution.Ready).value)
    }

    @Test fun networkFailuresStopAfterFiveTotalAttempts() = runBlocking {
        for (attempt in 0..3) {
            assertTrue(resolveAutomaticShare(attempt) { throw SocketTimeoutException() }
                is AutomaticShareResolution.Retry)
        }
        try {
            resolveAutomaticShare(4) { throw SocketTimeoutException() }
            fail("Retry budget must end")
        } catch (_: SocketTimeoutException) { }
    }

    @Test fun connectionAndTemporaryProviderFailuresCanRetry() = runBlocking {
        assertTrue(resolveAutomaticShare(0) { throw ConnectException() } is AutomaticShareResolution.Retry)
        assertTrue(resolveAutomaticShare(0) {
            throw ProviderException(ProviderException.Reason.TEMPORARY_FAILURE, "HTTP 503", "instagram")
        } is AutomaticShareResolution.Retry)
    }

    @Test fun permanentErrorsAndCancellationAreNeverRetried() = runBlocking {
        val errors = listOf(
            ProviderException(ProviderException.Reason.ACCESS_REQUIRED, "login"),
            ProviderException(ProviderException.Reason.UNSUPPORTED, "missing"),
            ProviderException(ProviderException.Reason.RATE_LIMITED, "limit"),
            IOException("disk"), SSLHandshakeException("certificate"),
            CancellationException("stopped"),
        )
        for (error in errors) {
            try {
                resolveAutomaticShare(0) { throw error }
                fail("Must propagate ${error.javaClass.simpleName}")
            } catch (actual: Exception) {
                assertSame(error, actual)
            }
        }
    }
}
