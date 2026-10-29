package com.domenota.medialoader.data.youtube

/** Ephemeral browser proof; never persist or log its values. */
object YouTubeLocalProof {
    class Value(val visitorData: String, val token: String, val createdAt: Long) {
        override fun toString(): String = "YouTubeLocalProof.Value(<redacted>)"
    }

    private var current: Value? = null
    private var probed = false
    private const val MAX_AGE_MS = 10 * 60_000L
    private val visitorPattern = Regex("[A-Za-z0-9_%=.-]+")
    private val tokenPattern = Regex("[A-Za-z0-9_+./=-]+")

    /** Fixed categories only: diagnostic output must not contain either proof value. */
    fun rejectionReason(visitorData: String, token: String): String? = when {
        visitorData.length !in 8..2048 -> "visitor-length"
        !visitorPattern.matches(visitorData) -> "visitor-chars"
        token.length !in 12..4096 -> "token-length"
        !tokenPattern.matches(token) -> "token-chars"
        else -> null
    }

    @Synchronized
    fun offer(visitorData: String, token: String, now: Long = System.currentTimeMillis()): Boolean {
        if (rejectionReason(visitorData, token) != null) return false
        current = Value(visitorData, token, now)
        return true
    }

    @Synchronized
    fun available(now: Long = System.currentTimeMillis()): Value? =
        current?.takeIf { now - it.createdAt in 0..MAX_AGE_MS }

    @Synchronized
    fun claimProbe(now: Long = System.currentTimeMillis()): Value? {
        if (probed) return null
        val value = current?.takeIf { now - it.createdAt in 0..MAX_AGE_MS } ?: return null
        probed = true
        current = null
        return value
    }

    @Synchronized
    fun clear() {
        current = null
        probed = false
    }
}
