package com.domenota.medialoader.data.download

/**
 * In-process, observation-only service status. A process restart resets it to
 * unknown. This is NOT a claim that Android will keep the service alive.
 */
object ForegroundServiceObservation {
    @Volatile private var foregroundCallSucceeded = false

    fun onForegroundStarted() { foregroundCallSucceeded = true }
    fun onServiceDestroyed() { foregroundCallSucceeded = false }
    fun isForegroundObserved(): Boolean = foregroundCallSucceeded
}
