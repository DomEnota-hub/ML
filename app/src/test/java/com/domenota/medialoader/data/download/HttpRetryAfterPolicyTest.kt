package com.domenota.medialoader.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpRetryAfterPolicyTest {
    @Test fun delaySecondsAreConvertedToMillis() {
        assertEquals(5_000L, HttpRetryAfterPolicy.delayMs("5"))
        assertEquals(0L, HttpRetryAfterPolicy.delayMs("0"))
        assertNull(HttpRetryAfterPolicy.delayMs("-1"))
    }

    @Test fun malformedHeaderIsNotAReasonForAnotherRetry() {
        assertNull(HttpRetryAfterPolicy.delayMs(""))
        assertNull(HttpRetryAfterPolicy.delayMs("retry whenever"))
    }

    @Test fun httpDateIsRelativeToKnownClock() {
        assertEquals(10_000L, HttpRetryAfterPolicy.delayMs(
            "Wed, 21 Oct 2015 07:28:10 GMT",
            1_445_412_480_000L,
        ))
    }
}
