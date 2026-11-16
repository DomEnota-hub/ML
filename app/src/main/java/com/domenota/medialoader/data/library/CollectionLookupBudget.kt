package com.domenota.medialoader.data.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Provider calls may block despite coroutine cancellation. Await a bounded worker
 * rather than joining a cancelled provider on the screen's effect. At most two
 * provider calls (including stuck providers) can own permits; queued obsolete
 * passes are cancelled on timeout or navigation. No result is published by a worker. */
internal object CollectionLookupBudget {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(2)
    suspend fun <T> run(timeoutMs: Long, block: suspend () -> T): T? {
        val worker = scope.async { permits.withPermit { block() } }
        return try {
            withTimeoutOrNull(timeoutMs) { worker.await() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        finally { worker.cancel() }
    }
}

/** One deadline for a collection, but a slow individual file cannot erase all
 * already resolved members. Expired lookups return UNCHECKED, not MISSING. */
internal class CollectionLookupDeadline(totalMs: Long = 12_000L, private val perAddressMs: Long = 1_500L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val deadline = clock() + totalMs
    suspend fun lookup(block: suspend () -> AddressedFile): AddressedFile {
        val remaining = (deadline - clock()).coerceAtMost(perAddressMs)
        return if (remaining <= 0) AddressedFile(null, com.domenota.medialoader.data.storage.SavedFileStatus.UNCHECKED)
        else CollectionLookupBudget.run(remaining, block) ?: AddressedFile(null, com.domenota.medialoader.data.storage.SavedFileStatus.UNCHECKED)
    }
}
