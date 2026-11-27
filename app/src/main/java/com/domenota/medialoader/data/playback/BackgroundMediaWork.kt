package com.domenota.medialoader.data.playback

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/** Playback and editing take priority over speculative preparation of library waveforms. */
internal object BackgroundMediaWork {
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val started = System.nanoTime()
    fun setBusy(owner: String, value: Boolean) {
        if (value) busy.add(owner) else busy.remove(owner)
    }
    fun isBusy():Boolean=busy.isNotEmpty() || (System.nanoTime() - started) / 1_000_000L < 2_500L
    suspend fun awaitIdle() {
        while (isBusy()) {
            delay(250L)
        }
    }
}
