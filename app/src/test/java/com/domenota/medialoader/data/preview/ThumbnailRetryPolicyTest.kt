package com.domenota.medialoader.data.preview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailRetryPolicyTest {
    @Test fun forbiddenUrlIsSuppressedButChangedUrlCanLoad() {
        var now = 0L
        val policy = ThumbnailRetryPolicy { now }
        val old = "https://scontent.example.com/old.jpg"
        policy.failed(old, 403)

        now = 60_000L
        assertFalse(policy.canAttempt(old))
        assertTrue(policy.canAttempt("https://scontent.example.com/new.jpg"))

        now = 30 * 60_000L
        assertTrue(policy.canAttempt(old))
    }

    @Test fun temporaryFailuresUseBoundedBackoffAndSuccessResetsIt() {
        var now = 0L
        val policy = ThumbnailRetryPolicy { now }
        val url = "https://scontent.example.com/preview.jpg"
        policy.failed(url, 429)
        now = 29_000L
        assertFalse(policy.canAttempt(url))
        now = 30_000L
        assertTrue(policy.canAttempt(url))
        policy.failed(url, 429)
        now = 89_000L
        assertFalse(policy.canAttempt(url))
        now = 90_000L
        assertTrue(policy.canAttempt(url))
        policy.succeeded(url)
        assertTrue(policy.canAttempt(url))
    }

    @Test fun networkErrorsWaitBeforeNextAttempt() {
        var now = 0L
        val policy = ThumbnailRetryPolicy { now }
        val url = "https://scontent.example.com/offline.jpg"
        policy.failed(url, null)
        now = 14_000L
        assertFalse(policy.canAttempt(url))
        now = 15_000L
        assertTrue(policy.canAttempt(url))
    }
}
