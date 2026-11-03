package com.domenota.medialoader.core.logging

/** Bounded samples per stall, using uptime so sleeping devices are not blamed on the UI. */
internal class StallWindow {
    private var reports = 0
    private var lastReport = 0L
    fun shouldReport(blockedMs: Long): Boolean {
        if (blockedMs < 700 || reports >= 3) return false
        if (reports > 0 && blockedMs - lastReport < 2000) return false
        reports++; lastReport = blockedMs
        return true
    }
    fun reset() { reports = 0; lastReport = 0 }
}
