package com.domenota.medialoader.ui

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*

/** Actual access epochs, never progress revisions, authorize retained raw models. */
internal data class LibraryMaterializationRequest<I>(
    val input: I, val access: Any?, val recovering: Boolean,
    val knownFolder: Boolean, val valid: Boolean, val bootstrapKey: Any? = null,
)
private data class MaterializedLibrary<T>(val access: Any?, val value: T)
private data class MaterializationKey(val access: Any, val bootstrap: Any?)
private class MaterializationToken(val key: MaterializationKey)
private data class MaterializationWork<I>(val request: LibraryMaterializationRequest<I>, val token: MaterializationToken?)
private data class LibraryMaterializationUpdate<T>(val access: Any?, val value: T?, val token: MaterializationToken? = null)

/** Coalesce identical cold milestones BEFORE latest-work cancellation. A milestone
 * is accepted only by the downstream fold, never merely because its builder ran.
 * Failed/cancelled builders release their token so the same key can retry. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <I, T> Flow<LibraryMaterializationRequest<I>>.materializeLibraryReadModel(
    empty: T, build: suspend (I) -> T,
): Flow<T> = flow {
    var knownBatch = false
    val accepted = AtomicReference<MaterializationKey?>(null)
    val active = AtomicReference<MaterializationToken?>(null)
    emitAll(this@materializeLibraryReadModel.transform { request ->
        knownBatch = request.recovering && (knownBatch || request.knownFolder)
        if (!request.valid || request.access == null) {
            accepted.set(null)
            active.set(null)
            emit(MaterializationWork(request, null))
        } else if (knownBatch && request.bootstrapKey == null) {
            active.set(null)
            emit(MaterializationWork(request, null))
        } else {
            val key = MaterializationKey(request.access, request.bootstrapKey)
            when {
                knownBatch && active.get()?.key == key -> Unit
                knownBatch && accepted.get() == key -> emit(MaterializationWork(request, null))
                else -> {
                    val token = MaterializationToken(key)
                    active.set(token)
                    emit(MaterializationWork(request, token))
                }
            }
        }
    }.transformLatest { work ->
        val request = work.request
        val token = work.token
        if (token == null) {
            emit(LibraryMaterializationUpdate<T>(request.access.takeIf { request.valid }, null))
        } else {
            var emitted = false
            try {
                val value = build(request.input)
                currentCoroutineContext().ensureActive()
                emit(LibraryMaterializationUpdate(request.access, value, token))
                emitted = true
            } finally {
                // Keep a successful in-flight token until the fold acknowledges it;
                // a rendezvous send returning is not itself publication acknowledgement.
                if (!emitted) active.compareAndSet(token, null)
            }
        }
    }.buffer(0).runningFold(MaterializedLibrary<T>(null, empty)) { previous, update ->
        if (update.token != null) {
            accepted.set(update.token.key)
            active.compareAndSet(update.token, null)
        } else if (update.access == null) accepted.set(null)
        MaterializedLibrary(update.access, update.value ?: previous.value.takeIf {
            update.access != null && previous.access == update.access
        } ?: empty)
    }.map { it.value })
}
