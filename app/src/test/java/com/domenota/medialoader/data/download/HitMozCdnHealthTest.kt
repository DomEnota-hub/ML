package com.domenota.medialoader.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HitMozCdnHealthTest {
    @Test fun twoFailedAttemptsPauseOnlyTheSameVerifiedCdn() {
        val health = HitMozCdnHealth()
        val now = 1_000_000L
        health.failed("cdn12.deliciousoranges.com", now)
        assertFalse(health.coolingDown("cdn12.deliciousoranges.com", now))
        health.failed("cdn12.deliciousoranges.com", now + 1000)
        assertTrue(health.coolingDown("cdn12.deliciousoranges.com", now + 1000))
        assertFalse(health.coolingDown("cdn13.deliciousoranges.com", now + 1000))
        assertFalse(health.coolingDown("evil.example.com", now + 1000))
    }

    @Test fun cooldownExpiresAndSuccessfulTransferClearsIt() {
        val health = HitMozCdnHealth()
        health.failed("s1.deliciouspeaches.com", 10_000)
        health.failed("s1.deliciouspeaches.com", 20_000)
        assertTrue(health.coolingDown("s1.deliciouspeaches.com", 20_000))
        assertFalse(health.coolingDown("s1.deliciouspeaches.com", 141_000))
        health.recovered("s1.deliciouspeaches.com")
        assertFalse(health.coolingDown("s1.deliciouspeaches.com", 20_001))
    }

    @Test fun failuresOutsideWindowDoNotAccumulateForever() {
        val health = HitMozCdnHealth()
        health.failed("cdn12.deliciousoranges.com", 0)
        health.failed("cdn12.deliciousoranges.com", 400_000)
        assertFalse(health.coolingDown("cdn12.deliciousoranges.com", 400_000))
    }
}
