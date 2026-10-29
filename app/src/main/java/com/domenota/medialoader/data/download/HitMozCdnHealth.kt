package com.domenota.medialoader.data.download

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A short-lived network diagnostic guard. Two failures against the same
 * CDN host trigger a brief pause; no hostname is permanently blocked.
 * The original HitMoz URL is re-requested on every retry; if it chooses a
 * different verified CDN, downloading continues normally.
 */
internal class HitMozCdnHealth(
    private val cooldownMs: Long = 120_000L,
    private val failureWindowMs: Long = 5 * 60_000L,
) {
    private data class Failure(val count: Int, val at: Long)
    private val failures = ConcurrentHashMap<String, Failure>()

    fun failed(host: String, now: Long) {
        if (!isMediaCdn(host)) return
        val name = host.lowercase(Locale.ROOT)
        failures.compute(name) { _, previous ->
            if (previous == null || now - previous.at > failureWindowMs) Failure(1, now)
            else Failure((previous.count + 1).coerceAtMost(10), now)
        }
        if (failures.size > 80) failures.entries.removeIf { now - it.value.at > failureWindowMs }
    }

    fun recovered(host: String) { failures.remove(host.lowercase(Locale.ROOT)) }

    fun coolingDown(host: String, now: Long): Boolean {
        if (!isMediaCdn(host)) return false
        val record = failures[host.lowercase(Locale.ROOT)] ?: return false
        return record.count >= 2 && now - record.at in 0 until cooldownMs
    }

    private fun isMediaCdn(host: String): Boolean {
        val name = host.lowercase(Locale.ROOT)
        return name.matches(Regex("cdn[0-9]{1,3}\\.deliciousoranges\\.com")) ||
            name.matches(Regex("(?:s|cdn)[0-9]{1,3}\\.deliciouspeaches\\.com"))
    }
}
