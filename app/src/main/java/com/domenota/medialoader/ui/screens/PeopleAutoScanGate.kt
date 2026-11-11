package com.domenota.medialoader.ui.screens

/** One automatic scan per opened image; edits only qualify for manual rescans. */
internal class PeopleAutoScanGate {
    private var claimed = false

    fun claim(eligible: Boolean): Boolean {
        if (!eligible || claimed) return false
        claimed = true
        return true
    }
}
