package com.domenota.medialoader.data.catalog.visibility

import kotlinx.coroutines.CancellationException

/** Fixed diagnostic labels only. No private locator, file name or exception message is exported. */
internal enum class VisibilityOperationStage(val label: String) {
    ACCESS("Проверка доступа"), PLAN("Подготовка плана"), LEGACY("Проверка сохранённого плана"),
    PREFLIGHT("Проверка исходных файлов"), BINDING_READ("Проверка записи файла"),
    SOURCE("Проверка исходного файла"), ALLOCATE("Подготовка новой копии"),
    COPY("Копирование файла"), DESTINATION("Проверка новой копии"),
    REBIND("Сохранение связей файла"), BINDING_VERIFY("Проверка связей файла"),
    CLEANUP("Подтверждение удаления исходной копии"), FINAL_VERIFY("Проверка скрытия файла"),
    RECEIPT("Проверка подтверждённого переноса"), MEMBERSHIP("Проверка состава папки"),
    COMMIT("Сохранение видимости папки"), JOURNAL("Чтение журнала переноса"),
}
internal data class VisibilityFailureInfo(val stage: VisibilityOperationStage, val code: String, val message: String)
internal class VisibilityOperationFailure(val info: VisibilityFailureInfo, val originalType: String,
    trace: Array<StackTraceElement>) : IllegalStateException(info.message) {
    init { stackTrace = trace }
    companion object {
        fun wrap(stage: VisibilityOperationStage, error: Exception): VisibilityOperationFailure {
            if (error is CancellationException) throw error
            if (error is VisibilityOperationFailure) return error
            val permission = stage == VisibilityOperationStage.ACCESS || generateSequence<Throwable>(error) { it.cause }
                .take(8).any { it is SecurityException }
            val noSpace = error.message == "Недостаточно свободного места"
            val kind = if(permission) "ACCESS" else if(noSpace) "SPACE" else if(error is java.io.IOException) "IO" else "STATE"
            val message = when {
                permission -> "Доступ к исходным файлам не подтверждён. План и проверенные копии сохранены."
                noSpace -> "Недостаточно свободного места. План и исходные файлы сохранены."
                else -> "${stage.label}: перенос остановлен. План и проверенные копии сохранены."
            }
            return VisibilityOperationFailure(VisibilityFailureInfo(stage,"FV_${stage.name}_$kind",message),
                error.javaClass.simpleName,error.stackTrace)
        }
    }
}

/** Reports the actual port boundary without publishing a new UI/catalog state per verification call. */
internal class ReportingVisibilityPorts(
    private val files: VisibilityMovePorts,
    private val batches: VisibilityBatchPorts,
    private val onStage: (VisibilityOperationStage) -> Unit,
) : VisibilityMovePorts by files, VisibilityBatchPorts by batches {
    private suspend fun <T> at(stage: VisibilityOperationStage, block: suspend () -> T): T {
        onStage(stage)
        try { return block() } catch(error: Exception) { throw VisibilityOperationFailure.wrap(stage,error) }
    }
    override suspend fun currentUri(record: VisibilityMoveRecord) = at(VisibilityOperationStage.BINDING_READ) { files.currentUri(record) }
    override suspend fun fingerprint(uri: String) = at(VisibilityOperationStage.SOURCE) { files.fingerprint(uri) }
    override suspend fun presence(uri: String) = at(VisibilityOperationStage.SOURCE) { files.presence(uri) }
    override suspend fun fingerprint(record: VisibilityMoveRecord, uri: String) = at(if(uri==record.destinationUri) VisibilityOperationStage.DESTINATION else VisibilityOperationStage.SOURCE) { files.fingerprint(record,uri) }
    override suspend fun presence(record: VisibilityMoveRecord, uri: String) = at(if(uri==record.destinationUri) VisibilityOperationStage.DESTINATION else VisibilityOperationStage.SOURCE) { files.presence(record,uri) }
    override suspend fun allocate(record: VisibilityMoveRecord) = at(VisibilityOperationStage.ALLOCATE) { files.allocate(record) }
    override suspend fun copyAndSync(record: VisibilityMoveRecord) = at(VisibilityOperationStage.COPY) { files.copyAndSync(record) }
    override suspend fun verifyDestination(record: VisibilityMoveRecord) = at(VisibilityOperationStage.DESTINATION) { files.verifyDestination(record) }
    override suspend fun rebind(record: VisibilityMoveRecord) = at(VisibilityOperationStage.REBIND) { files.rebind(record) }
    override suspend fun verifyBinding(record: VisibilityMoveRecord) = at(VisibilityOperationStage.BINDING_VERIFY) { files.verifyBinding(record) }
    override suspend fun removeSource(record: VisibilityMoveRecord) = at(VisibilityOperationStage.CLEANUP) { files.removeSource(record) }
    override suspend fun verifyFinalVisibility(record: VisibilityMoveRecord) = at(VisibilityOperationStage.FINAL_VERIFY) { files.verifyFinalVisibility(record) }
    override suspend fun preflight(batch: VisibilityBatchRecord) = at(VisibilityOperationStage.PREFLIGHT) { batches.preflight(batch) }
    override suspend fun prepareMembership(batch: VisibilityBatchRecord, pending: VisibilityMoveRecord?) = at(VisibilityOperationStage.MEMBERSHIP) { batches.prepareMembership(batch,pending) }
    override suspend fun completed(member: VisibilityMoveRecord) = at(VisibilityOperationStage.RECEIPT) { batches.completed(member) }
    override suspend fun commitVisibility(batch: VisibilityBatchRecord) = at(VisibilityOperationStage.COMMIT) { batches.commitVisibility(batch) }
}
