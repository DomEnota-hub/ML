package com.domenota.medialoader.data.catalog.visibility

/** Enrich recovery metadata under the existing operation gate, never rebuild a plan.
 * Batch-first ordering preserves exact evidence if the process dies between CASes. */
internal class VisibilityLegacyUpgrade(
    private val batches: VisibilityBatchJournal,
    private val moves: VisibilityMoveJournal,
    private val enrich: suspend (VisibilityMoveRecord) -> VisibilityMoveRecord,
) {
    suspend fun run() {
        val batch = batches.read()
        val pending = moves.read()
        if (batch?.members.orEmpty().all { it.publicOrigin != null } &&
            (pending == null || pending.publicOrigin != null)) return
        checkNotNull(batch) { "Старый перенос не содержит точного плана папки; журналы сохранены" }

        // Complete every read/hash and validate every result before changing either journal.
        val members = batch.members.map { member ->
            if (member.publicOrigin != null) member else upgrade(member)
        }
        val upgradedBatch = batch.copy(members = members)
        val upgradedMove = if (pending == null) null else if (pending.publicOrigin != null) {
            check(pending.copy(phase = VisibilityMovePhase.PREPARED, destinationUri = null) == members.getOrNull(batch.next)) {
                "Перенос не совпадает с обновлённым планом; журналы сохранены"
            }
            pending
        } else {
            val oldMember = batch.members.getOrNull(batch.next)
            val durableMember = members.getOrNull(batch.next)
            check(oldMember != null && durableMember != null) {
                "Старый перенос не соответствует позиции плана; журналы сохранены"
            }
            val prepared = pending.copy(phase = VisibilityMovePhase.PREPARED, destinationUri = null)
            if (oldMember.publicOrigin == null) check(prepared == oldMember) {
                "Старый перенос относится к другому файлу; журналы сохранены"
            }
            // Also works on restart after only the upgraded batch reached disk.
            validateUpgrade(prepared, durableMember)
            durableMember.copy(phase = pending.phase, destinationUri = pending.destinationUri).also {
                validateUpgrade(pending, it)
            }
        }
        if (upgradedBatch != batch) batches.replace(batch, upgradedBatch)
        if (upgradedMove != pending) moves.replace(pending, upgradedMove)
    }

    private suspend fun upgrade(record: VisibilityMoveRecord): VisibilityMoveRecord {
        check(record.direction == VisibilityDirection.HIDE) {
            "В старом журнале показа нет исходного расположения; журналы сохранены, требуется точное подтверждение"
        }
        return enrich(record).also { validateUpgrade(record, it) }
    }

    private fun validateUpgrade(old: VisibilityMoveRecord, upgraded: VisibilityMoveRecord) {
        check(old.publicOrigin == null && upgraded.publicOrigin != null && old.direction == VisibilityDirection.HIDE &&
            old.operationId == upgraded.operationId && old.folderId == upgraded.folderId &&
            old.downloadId == upgraded.downloadId && old.direction == upgraded.direction &&
            old.logicalSourceUri == upgraded.logicalSourceUri &&
            old.sourceUri in setOf(upgraded.sourceUri, upgraded.logicalSourceUri) &&
            old.sourceName == upgraded.sourceName && old.fingerprint == upgraded.fingerprint &&
            old.destinationToken == upgraded.destinationToken && old.destinationUri == upgraded.destinationUri &&
            old.phase == upgraded.phase &&
            (old.sourceModifiedMillis == null || old.sourceModifiedMillis == upgraded.sourceModifiedMillis)) {
            "Метаданные старого переноса не совпадают с точным планом; журналы сохранены"
        }
    }
}
