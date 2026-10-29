package com.domenota.medialoader.data.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class FileBatchResult(val succeeded: Int, val failed: Int) {
    val summary: String get() = "Успешно: $succeeded · Ошибок: $failed"
}

/** Snapshot all targets before mutation; never silently stop at the first failed file. */
suspend fun runFileBatch(ids: List<String>, action: suspend (String) -> Unit): FileBatchResult {
    var succeeded = 0
    var failed = 0
    for (id in ids.distinct()) {
        currentCoroutineContext().ensureActive()
        try { action(id); currentCoroutineContext().ensureActive(); succeeded++ }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed++ }
    }
    currentCoroutineContext().ensureActive()
    return FileBatchResult(succeeded, failed)
}
