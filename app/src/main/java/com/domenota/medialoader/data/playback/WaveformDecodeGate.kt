package com.domenota.medialoader.data.playback

import java.util.concurrent.CancellationException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Cached reads bypass this gate; only expensive uncached PCM decoders compete. */
internal class WaveformDecodeGate {
    private val permit = Semaphore(1, true)
    fun <T> decode(cancelled: () -> Boolean = { false }, block: () -> T): T {
        while (true) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Waveform request stopped")
            if (permit.tryAcquire(50, TimeUnit.MILLISECONDS)) break
        }
        return try {
            if (cancelled()) throw CancellationException("Waveform request stopped")
            block()
        } finally { permit.release() }
    }
    companion object { val shared = WaveformDecodeGate() }
}
