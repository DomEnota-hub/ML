package com.domenota.medialoader.data.download

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HitMozNetworkFailureTest {
    @Test fun recognizesCronetDnsFailuresDespiteObfuscatedClassNames() {
        val exception = IOException("Exception in CronetUrlRequest: net::ERR_NAME_NOT_RESOLVED, ErrorCode=1")
        assertEquals(HitMozNetworkFailure.Kind.DNS, HitMozNetworkFailure.classify(exception))
        assertEquals(HitMozNetworkFailure.Kind.DNS,
            HitMozNetworkFailure.classify(UnknownHostException("cdn12.deliciousoranges.com")))
    }

    @Test fun recognizesTimeoutAndSocketResetFromAndroidFallback() {
        assertEquals(HitMozNetworkFailure.Kind.TIMEOUT,
            HitMozNetworkFailure.classify(SocketTimeoutException("Read timed out")))
        assertEquals(HitMozNetworkFailure.Kind.RESET,
            HitMozNetworkFailure.classify(SocketException("Connection reset")))
    }

    @Test fun checksNestedCausesWithoutCopyingUnsafeExceptionMessages() {
        val nested = IOException("generic failure", UnknownHostException("host.example"))
        assertEquals(HitMozNetworkFailure.Kind.DNS, HitMozNetworkFailure.classify(nested))
        assertEquals(HitMozNetworkFailure.Kind.OTHER,
            HitMozNetworkFailure.classify(IOException("HTTP 403 response")))
        assertFalse(HitMozNetworkFailure.isNetworkFailure(HitMozNetworkFailure.Kind.OTHER))
    }

    @Test fun reportsSpecificFailureOnlyIfSupportedByAllAttempts() {
        assertTrue(HitMozNetworkFailure.failedMessage(setOf(HitMozNetworkFailure.Kind.DNS))
            .contains("сетевой адрес"))
        assertTrue(HitMozNetworkFailure.failedMessage(
            setOf(HitMozNetworkFailure.Kind.DNS, HitMozNetworkFailure.Kind.TIMEOUT))
            .contains("расширенный журнал"))
    }
}
