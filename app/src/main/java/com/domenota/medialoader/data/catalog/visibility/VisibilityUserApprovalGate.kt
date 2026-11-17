package com.domenota.medialoader.data.catalog.visibility

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An in-memory, one-time Activity approval channel, never durable permission.
 *
 * The service can request user consent, but only a visible Activity may launch
 * the Android PendingIntent. No active Activity means the request waits with a
 * bound and then fails CLOSED. After process death the durable batch journal,
 * not this ticket, is the source of truth; explicit replay creates a fresh
 * Android request, not a synthetic approval.
 */
internal data class VisibilityApprovalTicket<T : Any>(val id: Long, val payload: T)

internal class VisibilityUserApprovalGate<T : Any>(
    private val timeoutMillis: Long = 120_000L,
) {
    init { require(timeoutMillis > 0) }
    private val lock = Any()
    private var sequence = 0L
    private var active: CompletableDeferred<Boolean>? = null
    private var claimed = false
    private val current = MutableStateFlow<VisibilityApprovalTicket<T>?>(null)
    val pending = current.asStateFlow()

    suspend fun request(payload: T): Boolean {
        val decision = CompletableDeferred<Boolean>()
        val id: Long
        synchronized(lock) {
            check(active == null) { "Уже ожидается подтверждение Android" }
            active = decision
            claimed = false
            id = ++sequence
            current.value = VisibilityApprovalTicket(id, payload)
        }
        return try {
            withTimeout(timeoutMillis) { decision.await() }
        } finally {
            synchronized(lock) {
                if (active === decision) {
                    active = null
                    claimed = false
                    current.value = null
                }
            }
        }
    }

    /** Acquires the one-time launch for one concrete ActivityResultLauncher. */
    fun claim(id: Long): Boolean = synchronized(lock) {
        if (current.value?.id != id || active == null || claimed) false
        else { claimed = true; true }
    }

    /** A stale Activity, a duplicated callback or another operation cannot approve. */
    fun decide(id: Long, approved: Boolean): Boolean = synchronized(lock) {
        val decision = active
        if (current.value?.id != id || decision == null || !claimed || decision.isCompleted) false
        else { decision.complete(approved); true }
    }

    /** Reject a vanished Activity's pending request instead of silently approving it. */
    fun rejectClaimed(id: Long): Boolean = synchronized(lock) {
        val decision = active
        if (current.value?.id != id || decision == null || !claimed || decision.isCompleted) false
        else { decision.complete(false); true }
    }
}
