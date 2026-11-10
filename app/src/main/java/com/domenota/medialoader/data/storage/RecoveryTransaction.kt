package com.domenota.medialoader.data.storage

/** No Android dependencies. Every port must accept only the identity in the durable plan. */
internal enum class RecoveryStage {
    PREPARED, COPY_PENDING, METADATA_VERIFIED, LINKED_MAIN, LINKED_VK,
    LINKED_CATALOG, LINKED_AUX, REOPEN_VERIFIED, AWAITING_CLEANUP, DONE,
}

internal data class RecoveryRecord(
    val operation: String,
    val stage: RecoveryStage = RecoveryStage.PREPARED,
    val rollingBack: Boolean = false,
    val rollbackStep: Int = 0,
    val rolledBack: Boolean = false,
)

internal interface RecoveryJournal {
    fun read(): RecoveryRecord
    fun write(record: RecoveryRecord)
}

/** Calls are idempotent even if the process dies after the effect and before journal advancement. */
internal interface RecoveryPorts {
    fun verifyIdentityAndBackup()
    fun copyPending()
    fun verifyMetadataAndContent()
    fun switchMain(forward: Boolean)
    fun switchVk(forward: Boolean)
    fun switchCatalog(forward: Boolean)
    fun switchPlaylists(forward: Boolean)
    fun verifyReopened()
    fun verifyOriginalRetained()
    fun verifyRollback()
}

internal class RecoveryTransaction(private val journal: RecoveryJournal, private val ports: RecoveryPorts) {
    fun resume(): RecoveryRecord {
        var record = journal.read()
        if (record.rolledBack) { ports.verifyRollback(); return record }
        if (record.rollingBack) return rollback()
        ports.verifyIdentityAndBackup()
        while (record.stage != RecoveryStage.DONE) {
            when (record.stage) {
                RecoveryStage.PREPARED -> ports.copyPending()
                RecoveryStage.COPY_PENDING -> ports.verifyMetadataAndContent()
                RecoveryStage.METADATA_VERIFIED -> ports.switchMain(true)
                RecoveryStage.LINKED_MAIN -> ports.switchVk(true)
                RecoveryStage.LINKED_VK -> ports.switchCatalog(true)
                RecoveryStage.LINKED_CATALOG -> ports.switchPlaylists(true)
                RecoveryStage.LINKED_AUX -> ports.verifyReopened()
                RecoveryStage.REOPEN_VERIFIED, RecoveryStage.AWAITING_CLEANUP -> ports.verifyOriginalRetained()
                RecoveryStage.DONE -> Unit
            }
            record = record.copy(stage = RecoveryStage.entries[record.stage.ordinal + 1])
            journal.write(record)
        }
        // DONE retains both originals and backups. No cleanup/delete action exists.
        ports.verifyReopened()
        ports.verifyOriginalRetained()
        return record
    }

    fun rollback(): RecoveryRecord {
        var record = journal.read()
        if (record.rolledBack) { ports.verifyRollback(); return record }
        if (!record.rollingBack) {
            record = record.copy(rollingBack = true)
            journal.write(record) // rollback intent precedes any compensating write
        }
        ports.verifyIdentityAndBackup()
        while (record.rollbackStep < 5) {
            when (record.rollbackStep) {
                0 -> ports.switchPlaylists(false)
                1 -> ports.switchCatalog(false)
                2 -> ports.switchVk(false)
                3 -> ports.switchMain(false)
                4 -> ports.verifyRollback()
            }
            record = record.copy(rollbackStep = record.rollbackStep + 1)
            journal.write(record)
        }
        record = record.copy(rolledBack = true)
        journal.write(record)
        return record
    }
}
