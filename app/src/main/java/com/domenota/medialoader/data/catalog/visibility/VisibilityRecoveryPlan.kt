package com.domenota.medialoader.data.catalog.visibility

/**
 * Read-only bridge between the 1.7.203 durable journal format and any new
 * transfer implementation. Do not upgrade, discard or infer a missing move.
 *
 * Every existing checkpoint is an exact identity, not a position in a freshly
 * discovered album. Unknown and inconsistent states stop before any file write.
 */
internal data class VisibilityRecoveryPlan(
    val batchId: String,
    val direction: VisibilityDirection,
    val confirmed: Int,
    val total: Int,
    val active: VisibilityMoveRecord?,
)

internal object VisibilityRecoveryPlanner {
    fun inspect(batch: VisibilityBatchRecord?, pending: VisibilityMoveRecord?): VisibilityRecoveryPlan? {
        if (batch == null) {
            check(pending == null) { "Найден перенос без проверенного плана папки" }
            return null
        }
        check(batch.next in 0..batch.members.size)
        val member = batch.members.getOrNull(batch.next)
        if (pending != null) {
            check(member != null) { "Журнал файла остался после завершения плана папки" }
            check(pending.copy(destinationUri = null, phase = VisibilityMovePhase.PREPARED) == member) {
                "Незавершённый перенос не принадлежит текущему файлу папки"
            }
            // Process death is also legal between the initial journal CAS and
            // allocation. PREPARED has no destination and must be replayable.
            check((pending.phase == VisibilityMovePhase.PREPARED) == (pending.destinationUri == null))
        }
        return VisibilityRecoveryPlan(batch.id,batch.direction,batch.next,batch.members.size,pending)
    }
}
