package com.domenota.medialoader.data.catalog

import com.domenota.medialoader.data.tasks.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class FileBatchResult(val succeeded: Int, val failed: Int) {
    val summary: String get() = "Успешно: $succeeded · Ошибок: $failed"
}

/** Snapshot all targets before mutation; never silently stop at the first failed file. */
suspend fun runFileBatch(ids: List<String>, action: suspend (String) -> Unit): FileBatchResult = runFileBatch(ids,false,action)

/** Only callers with public selections opt in; private operations do not enter the public center. */
internal suspend fun runFileBatch(ids: List<String>, reportPublic:Boolean, action: suspend (String) -> Unit): FileBatchResult {
    var succeeded = 0
    var failed = 0
    val targets=ids.distinct()
    val reporter=if(reportPublic&&targets.size>1)TaskReporter.create(TaskOwner.FILES,TaskKind.FILE_OPERATION)else null
    reporter?.report(files=countProgress(0,targets.size))
    try {
    for (id in targets) {
        currentCoroutineContext().ensureActive()
        try { action(id); currentCoroutineContext().ensureActive(); succeeded++ }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed++ }
        reporter?.report(files=countProgress(succeeded,targets.size),errors=failed)
    }
    currentCoroutineContext().ensureActive()
    reporter?.report(when {failed==0->TaskStatus.COMPLETE;succeeded==0->TaskStatus.FAILED;else->TaskStatus.PARTIAL},files=countProgress(succeeded,targets.size),errors=failed)
    return FileBatchResult(succeeded, failed)
    } catch(e:CancellationException) { reporter?.report(TaskStatus.CANCELLED,files=countProgress(succeeded,targets.size),errors=failed); throw e }
}
