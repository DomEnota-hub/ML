package com.domenota.medialoader.ui

/**
 * Allows exactly one automatic recognition result per explicitly opened full-song
 * search session. Closing, navigating away or starting another session permanently
 * invalidates any delayed callbacks from the previous session.
 */
internal class FullSongSessionGate {
    private var currentId: Long = 0L
    private var accepted = false

    fun open(): Long {
        currentId += 1
        accepted = false
        return currentId
    }

    fun close() {
        currentId += 1
        accepted = true
    }

    fun acceptRecognition(id: Long): Boolean {
        if (id != currentId || accepted) return false
        accepted = true
        return true
    }
}
