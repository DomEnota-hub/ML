package com.domenota.medialoader.data.catalog.visibility

import java.util.Base64

/** A permanent receipt is needed both for the next SHOW and for recovery of
 * the gap between clearing the single-file journal and advancing the batch. */
internal object VisibilityReceiptPolicy {
    fun encode(record: VisibilityMoveRecord): String {
        require(record.phase == VisibilityMovePhase.CLEANED && record.publicOrigin != null)
        return Base64.getEncoder().encodeToString(VisibilityJournalCodec.encode(record))
    }
    fun decode(value: String): VisibilityMoveRecord {
        check(value.length <= (VisibilityJournalCodec.MAX_BYTES + 2) / 3 * 4)
        val record = requireNotNull(VisibilityJournalCodec.decode(Base64.getDecoder().decode(value)))
        check(record.phase == VisibilityMovePhase.CLEANED && record.publicOrigin != null)
        return record
    }
    fun validateTransition(previous: VisibilityMoveRecord?, next: VisibilityMoveRecord) {
        require(next.phase == VisibilityMovePhase.CLEANED && next.publicOrigin != null)
        if (previous == next) return // DB commit succeeded before the journal checkpoint.
        if (previous == null) {
            check(next.direction == VisibilityDirection.HIDE) { "Не найдено исходное расположение скрытого файла" }
        } else {
            check(previous.downloadId == next.downloadId && previous.destinationUri == next.sourceUri &&
                previous.direction != next.direction && previous.operationId != next.operationId) {
                "Состояние видимости файла изменилось"
            }
            if (next.direction == VisibilityDirection.SHOW) check(previous.publicOrigin == next.publicOrigin && previous.folderId == next.folderId) {
                "Исходное расположение или папка изменились"
            }
        }
    }
    /** Only a controlled, byte-verified editor commit may revise a hidden receipt.
     * Retain original placement, capture/added date and stable logical identity. */
    fun afterVerifiedEdit(previous: VisibilityMoveRecord, currentUri: String, expectedBefore: VisibilityFingerprint,
        actualAfter: VisibilityFingerprint, modifiedMillis: Long): VisibilityMoveRecord {
        check(previous.phase == VisibilityMovePhase.CLEANED && previous.direction == VisibilityDirection.HIDE &&
            previous.destinationUri == currentUri && previous.fingerprint == expectedBefore) { "Скрытый файл изменился" }
        check(modifiedMillis > 0 && modifiedMillis % 1000L == 0L) { "Дата редактирования не подтверждена" }
        val origin = requireNotNull(previous.publicOrigin)
        return previous.copy(fingerprint=actualAfter,sourceModifiedMillis=modifiedMillis,
            publicOrigin=origin.copy(bytes=actualAfter.bytes,modifiedSeconds=modifiedMillis/1000L))
    }

    fun confirms(receipt: VisibilityMoveRecord?, member: VisibilityMoveRecord): Boolean = receipt != null &&
        receipt.phase == VisibilityMovePhase.CLEANED && receipt.copy(phase = VisibilityMovePhase.PREPARED,destinationUri = null) == member
}
