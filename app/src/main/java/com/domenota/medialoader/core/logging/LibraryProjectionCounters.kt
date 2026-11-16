package com.domenota.medialoader.core.logging

import java.util.concurrent.atomic.AtomicLongArray

internal enum class LibraryProjectionKind { ALIASES, PUBLIC_ROWS, AUDIO_SESSION, ASSIGNMENTS, GALLERY_BROWSE, MUSIC_BROWSE, OTHER }
internal enum class LibraryProjectionReason { INITIAL, INPUT, ACCESS }
internal class LibraryProjectionCounterSet(private val nanoTime: () -> Long = System::nanoTime) {
    private val counts = AtomicLongArray(LibraryProjectionKind.entries.size * 8)
    private fun slot(kind: LibraryProjectionKind, offset: Int) = kind.ordinal * 8 + offset
    internal fun start(kind: LibraryProjectionKind, reason: LibraryProjectionReason): Ticket {
        counts.incrementAndGet(slot(kind, reason.ordinal))
        return Ticket(kind, nanoTime())
    }
    internal fun reuse(kind: LibraryProjectionKind) { counts.incrementAndGet(slot(kind, 5)) }
    inner class Ticket(private val kind: LibraryProjectionKind, private val started: Long) {
        private var done = false
        fun complete() {
            if (done) return
            done = true
            val elapsed = ((nanoTime() - started).coerceAtLeast(0L) / 1_000_000L)
            counts.incrementAndGet(slot(kind, 3))
            counts.addAndGet(slot(kind, 6), elapsed)
            val maxSlot = slot(kind, 7)
            while (true) {
                val old = counts.get(maxSlot)
                if (old >= elapsed || counts.compareAndSet(maxSlot, old, elapsed)) break
            }
        }
        fun close() { if (!done) { done = true; counts.incrementAndGet(slot(kind, 4)) } }
    }
    fun summary(): String = LibraryProjectionKind.entries.joinToString(" ") { kind ->
        val values = (0..7).map { counts.get(slot(kind, it)) }
        "${kind.name}[initial=${values[0]},input=${values[1]},access=${values[2]},done=${values[3]},aborted=${values[4]},reuse=${values[5]},totalMs=${values[6]},maxMs=${values[7]}]"
    }
}
internal object LibraryProjectionCounters {
    private val counters = LibraryProjectionCounterSet()
    fun start(kind: LibraryProjectionKind, reason: LibraryProjectionReason) = counters.start(kind, reason)
    fun reuse(kind: LibraryProjectionKind) = counters.reuse(kind)
    fun summary() = counters.summary()
}
