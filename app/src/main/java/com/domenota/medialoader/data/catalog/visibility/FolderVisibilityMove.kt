package com.domenota.medialoader.data.catalog.visibility

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A folder visibility move is not a vault move or a trash operation. The original is
 * retained until both the destination bytes and the new catalog binding are verified.
 * A port MUST resolve only the exact, journaled identity, never a name-based candidate. */
internal enum class VisibilityDirection { HIDE, SHOW }
internal enum class VisibilityMovePhase { PREPARED, ALLOCATED, VERIFIED, LINKED, CLEANED }
internal enum class VisibilityPresence { PRESENT, MISSING, UNKNOWN }

internal data class VisibilityFingerprint(val bytes: Long, val sha256: String) {
    init { require(bytes > 0 && sha256.matches(Regex("[0-9a-f]{64}"))) }
}

internal data class VisibilityMoveRecord(
    val operationId: String,
    val folderId: String,
    val downloadId: String,
    val direction: VisibilityDirection,
    val sourceUri: String,
    val sourceName: String,
    val sourceModifiedMillis: Long?,
    val fingerprint: VisibilityFingerprint,
    val destinationToken: String,
    val destinationUri: String? = null,
    val phase: VisibilityMovePhase = VisibilityMovePhase.PREPARED,
    val publicOrigin: VisibilityPublicOrigin? = null,
    // The logical catalog may still use a proven file/SAF alias of the typed source.
    val catalogSourceUri: String? = null,
) {
    val logicalSourceUri: String get() = catalogSourceUri ?: sourceUri
    init {
        require(operationId.isNotBlank() && folderId.isNotBlank() && downloadId.isNotBlank())
        require(sourceUri.isNotBlank() && logicalSourceUri.isNotBlank() && sourceName.isNotBlank() && destinationToken.isNotBlank())
        require(sourceName != "." && sourceName != ".." && sourceName.none { it == '/' || it == '\\' || it.isISOControl() })
        require(sourceModifiedMillis == null || sourceModifiedMillis > 0)
        require(destinationUri == null || (destinationUri.isNotBlank() && destinationUri != sourceUri && destinationUri != logicalSourceUri))
        require((phase == VisibilityMovePhase.PREPARED) == (destinationUri == null))
        require(direction != VisibilityDirection.SHOW || logicalSourceUri == sourceUri)
        if (publicOrigin != null) {
            require(publicOrigin.name == sourceName && publicOrigin.bytes == fingerprint.bytes && publicOrigin.modifiedMillis == sourceModifiedMillis)
            require(direction != VisibilityDirection.HIDE || publicOrigin.uri == sourceUri)
        }
    }
}

internal interface VisibilityMoveJournal {
    suspend fun read(): VisibilityMoveRecord?
    /** Durable compare-and-set, not an asynchronous preference write. */
    suspend fun replace(expected: VisibilityMoveRecord?, next: VisibilityMoveRecord?)
}

/** All effects must be idempotent across process death, including allocation before its
 * returned locator is journaled. Never reuse a pre-existing unrelated destination. */
internal interface VisibilityMovePorts {
    suspend fun currentUri(record: VisibilityMoveRecord): String?
    suspend fun fingerprint(uri: String): VisibilityFingerprint
    suspend fun presence(uri: String): VisibilityPresence
    suspend fun fingerprint(record: VisibilityMoveRecord, uri: String): VisibilityFingerprint = fingerprint(uri)
    suspend fun presence(record: VisibilityMoveRecord, uri: String): VisibilityPresence = presence(uri)
    suspend fun allocate(record: VisibilityMoveRecord): String
    suspend fun copyAndSync(record: VisibilityMoveRecord)
    /** Verify exact destination ownership, name, chronology and visibility domain. */
    suspend fun verifyDestination(record: VisibilityMoveRecord)
    /** CAS identity + update dependent links, replayable if DB commit preceded journal. */
    suspend fun rebind(record: VisibilityMoveRecord)
    suspend fun verifyBinding(record: VisibilityMoveRecord)
    /** Check current references, ownership and source identity again before deletion. */
    suspend fun removeSource(record: VisibilityMoveRecord)
    /** HIDE: old public index absent and destination not indexed. SHOW: new public index present. */
    suspend fun verifyFinalVisibility(record: VisibilityMoveRecord)
}

/** This engine never deletes a destination or treats an unknown source as missing.
 * A failed/cancelled operation remains in the journal and can resume explicitly. */
internal class FolderVisibilityMove(
    private val journal: VisibilityMoveJournal,
    private val ports: VisibilityMovePorts,
) {
    suspend fun begin(record: VisibilityMoveRecord) = operationGate.withLock {
        require(record.phase == VisibilityMovePhase.PREPARED)
        check(journal.read() == null) { "Сначала завершите предыдущий перенос" }
        check(ports.currentUri(record) == record.sourceUri) { "Исходная запись изменилась" }
        verifySource(record)
        journal.replace(null, record)
        resumeLocked()
    }

    suspend fun resume() = operationGate.withLock { resumeLocked() }

    private suspend fun resumeLocked() {
        var record = journal.read() ?: return
        suspend fun advance(next: VisibilityMoveRecord) {
            journal.replace(record, next)
            record = next
        }
        while (true) {
            // A phase is timed without altering journaling order, CAS, fingerprinting,
            // cleanup, cancellation or existing recovery behavior.
            val measuredPhase = record.phase
            val measuredOperation = record.operationId
            val measuredDirection = record.direction
            val measuredBytes = record.fingerprint.bytes
            val startedAt = System.nanoTime()
            var stageCompleted = false
            try {
            when (record.phase) {
                VisibilityMovePhase.PREPARED -> {
                    check(ports.currentUri(record) == record.sourceUri)
                    verifySource(record)
                    val uri = ports.allocate(record)
                    check(uri.isNotBlank() && uri != record.sourceUri)
                    advance(record.copy(destinationUri = uri, phase = VisibilityMovePhase.ALLOCATED))
                }
                VisibilityMovePhase.ALLOCATED -> {
                    check(ports.currentUri(record) == record.sourceUri)
                    verifySource(record)
                    ports.copyAndSync(record)
                    verifyDestination(record)
                    // Detect a source changed concurrently during copy.
                    verifySource(record)
                    advance(record.copy(phase = VisibilityMovePhase.VERIFIED))
                }
                VisibilityMovePhase.VERIFIED -> {
                    verifySource(record)
                    verifyDestination(record)
                    check(ports.currentUri(record) in setOf(record.sourceUri, record.destinationUri))
                    ports.rebind(record)
                    verifyBinding(record)
                    advance(record.copy(phase = VisibilityMovePhase.LINKED))
                }
                VisibilityMovePhase.LINKED -> {
                    verifyDestination(record)
                    verifyBinding(record)
                    when (ports.presence(record, record.sourceUri)) {
                        VisibilityPresence.PRESENT -> {
                            verifySource(record)
                            ports.removeSource(record)
                        }
                        VisibilityPresence.MISSING -> Unit // process died after removal
                        VisibilityPresence.UNKNOWN -> error("Не удалось подтвердить состояние исходного файла")
                    }
                    check(ports.presence(record, record.sourceUri) == VisibilityPresence.MISSING) {
                        "Копия сохранена, удаление исходного экземпляра не подтверждено"
                    }
                    advance(record.copy(phase = VisibilityMovePhase.CLEANED))
                }
                VisibilityMovePhase.CLEANED -> {
                    verifyDestination(record)
                    verifyBinding(record)
                    check(ports.presence(record, record.sourceUri) == VisibilityPresence.MISSING)
                    ports.verifyFinalVisibility(record)
                    journal.replace(record, null)
                    stageCompleted = true
                    return
                }
            }
            stageCompleted = true
            } finally {
                if (com.domenota.medialoader.core.logging.AppLog.isDetailedPerformance()) {
                    com.domenota.medialoader.core.logging.AppLog.i("FolderVisibility",
                        "MOVE_PHASE operation=$measuredOperation phase=${measuredPhase.name} direction=${measuredDirection.name} " +
                            "bytes=$measuredBytes elapsedMs=${visibilityDurationMs(startedAt,System.nanoTime())} success=$stageCompleted")
                }
            }
        }
    }

    companion object {
        // The journal CAS alone cannot prevent duplicate side effects between readers.
        private val operationGate = Mutex()
    }

    private suspend fun verifySource(record: VisibilityMoveRecord) {
        check(ports.presence(record, record.sourceUri) == VisibilityPresence.PRESENT &&
            ports.fingerprint(record, record.sourceUri) == record.fingerprint) { "Исходный файл изменился или недоступен" }
    }
    private suspend fun verifyDestination(record: VisibilityMoveRecord) {
        val uri = requireNotNull(record.destinationUri)
        check(ports.presence(record, uri) == VisibilityPresence.PRESENT && ports.fingerprint(record, uri) == record.fingerprint) {
            "Контрольная сумма новой копии не совпала"
        }
        ports.verifyDestination(record)
    }
    private suspend fun verifyBinding(record: VisibilityMoveRecord) {
        check(ports.currentUri(record) == record.destinationUri) { "Каталог не подтвердил новый адрес" }
        ports.verifyBinding(record)
    }
}
