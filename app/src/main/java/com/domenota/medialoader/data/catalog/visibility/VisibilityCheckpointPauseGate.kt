package com.domenota.medialoader.data.catalog.visibility

import java.util.concurrent.atomic.AtomicBoolean

/** A cooperative pause request, intentionally *not* coroutine cancellation.
 * Interrupting an in-flight copy or permission-backed deletion may leave
 * partially applied effects. The owning batch calls checkpoint() only after
 * its compare-and-set cursor has been durably written and the move journal
 * is empty (or before the first member of a new durable batch).
 *
 * The next explicit manager.resume() must still perform normal preflight and
 * replay all existing journals. No automatic resume after process restart.
 */
internal class VisibilityPausedAtCheckpoint : RuntimeException("Перенос приостановлен после сохранения контрольной точки")

internal class VisibilityCheckpointPauseGate {
    private val requested = AtomicBoolean(false)
    fun request(): Boolean = requested.compareAndSet(false, true)
    fun reset() { requested.set(false) }
    fun checkpoint() {
        if (requested.getAndSet(false)) throw VisibilityPausedAtCheckpoint()
    }
}
