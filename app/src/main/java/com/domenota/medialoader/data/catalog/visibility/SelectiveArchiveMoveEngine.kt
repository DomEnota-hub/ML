package com.domenota.medialoader.data.catalog.visibility

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Archive transport is separate from the retired v2 physical-file mover.
 * Only a single exact MediaStore row may move. A copy, delete, trash request,
 * or a new MediaStore insert is NOT an implementation of this interface.
 */
internal interface SelectiveArchiveMovePort {
    /** Observe exact MediaStore ID, physical path, SHA-256 and owned marker.
     * UNKNOWN means no mutations are permitted. */
    suspend fun observe(
        original: SelectiveArchiveIdentity,
        receipt: SelectiveArchiveMoveReceipt,
    ): SelectiveArchiveMoveObservation

    /** Create/verify our OWN target and .nomedia. Must never touch a foreign
     * marker, unrecognized entry or a parent/shared directory. */
    suspend fun ensureTarget(
        original: SelectiveArchiveIdentity,
        receipt: SelectiveArchiveMoveReceipt,
    )

    /** Update the exact existing MediaStore record's RELATIVE_PATH. Never
     * issue INSERT, DELETE, RENAME, set IS_TRASHED or set IS_PENDING. */
    suspend fun changeRelativePath(
        original: SelectiveArchiveIdentity,
        receipt: SelectiveArchiveMoveReceipt,
        toHidden:Boolean,
    )
}

internal enum class SelectiveArchiveLocation { SOURCE, HIDDEN, UNKNOWN }

internal data class SelectiveArchiveMoveObservation(
    val location: SelectiveArchiveLocation,
    /** Observed record carries original typed URI and original catalogue ID. */
    val identity: SelectiveArchiveIdentity?,
    /** Must be false when hidden, true when restored. Null means unknown. */
    val visibleInAndroid:Boolean?,
    /** True only after independent equality of favorites/playlists/collections. */
    val referencesUnchanged:Boolean?,
    /** True only if target is exactly ML's own .nomedia and directory. */
    val targetOwned:Boolean?,
)

/**
 * Durable state machine. No UI callsite is enabled until an instrumented
 * device preflight + verified association adapter are available.
 * Incomplete operations remain recoverable by a later call; never silently
 * report a half-finished operation as success.
 */
internal class SelectiveArchiveMoveEngine(
    private val journal: SelectiveArchiveMoveJournal,
    private val port: SelectiveArchiveMovePort,
) {
    private val mutex=Mutex()

    private fun locationIsExact(
        observed:SelectiveArchiveMoveObservation,
        before:SelectiveArchiveIdentity,
        expected:SelectiveArchiveLocation,
        target:String,
    ):Boolean {
        val now=observed.identity ?: return false
        if(observed.location!=expected || !now.wellFormed() ||
            now.catalogId!=before.catalogId || now.originalUri!=before.originalUri ||
            now.mediaStoreId!=before.mediaStoreId || now.volume!=before.volume ||
            now.displayName!=before.displayName || now.sizeBytes!=before.sizeBytes ||
            now.sha256!=before.sha256 || now.modifiedAtMillis!=before.modifiedAtMillis ||
            now.referencesSha256!=before.referencesSha256 ||
            observed.referencesUnchanged!=true
        ) return false
        return now.relativePath==(if(expected==SelectiveArchiveLocation.SOURCE)
            before.relativePath else target)
    }

    suspend fun hide(
        checkpoint:SelectiveArchiveCheckpoint,
        original:SelectiveArchiveIdentity,
        token:String,
        targetRelativePath:String,
    ):SelectiveArchiveMoveReceipt=mutex.withLock {
        require(original in checkpoint.originals && checkpoint.folderId.isNotBlank())
        val existing=journal.begin(checkpoint,original,token,targetRelativePath)
        check(existing.phase in setOf(SelectiveArchiveMovePhase.PREPARED,
            SelectiveArchiveMovePhase.HIDE_INTENT,SelectiveArchiveMovePhase.HIDDEN_VERIFIED)) {
            "Операция уже восстанавливается или требует ручной проверки"
        }
        if(existing.phase==SelectiveArchiveMovePhase.HIDDEN_VERIFIED) {
            val read=port.observe(original,existing)
            check(locationIsExact(read,original,SelectiveArchiveLocation.HIDDEN,targetRelativePath) &&
                read.targetOwned==true && read.visibleInAndroid==false) {
                "Скрытое состояние изменилось; повторное скрытие запрещено"
            }
            return@withLock existing
        }
        // Before committing any intent, verify original public state and
        // reference snapshots. After the intent, a crash may have moved it.
        val intent=if(existing.phase==SelectiveArchiveMovePhase.PREPARED) {
            val before=port.observe(original,existing)
            check(locationIsExact(before,original,SelectiveArchiveLocation.SOURCE,targetRelativePath) &&
                before.visibleInAndroid==true) { "Публичный оригинал изменился" }
            journal.transition(existing,SelectiveArchiveMovePhase.HIDE_INTENT)
        } else existing
        val state=port.observe(original,intent)
        when {
            locationIsExact(state,original,SelectiveArchiveLocation.SOURCE,targetRelativePath) &&
                state.visibleInAndroid==true -> {
                port.ensureTarget(original,intent)
                // Recheck after creating the owned marker and before update.
                val unchanged=port.observe(original,intent)
                check(locationIsExact(unchanged,original,SelectiveArchiveLocation.SOURCE,targetRelativePath) &&
                    unchanged.visibleInAndroid==true) { "Оригинал изменён перед переносом" }
                port.changeRelativePath(original,intent,toHidden=true)
            }
            locationIsExact(state,original,SelectiveArchiveLocation.HIDDEN,targetRelativePath) &&
                state.targetOwned==true -> Unit // Crash occurred after the move.
            else -> error("Исходный MediaStore ID не найден в ожидаемом состоянии; перенос остановлен")
        }
        val after=port.observe(original,intent)
        check(locationIsExact(after,original,SelectiveArchiveLocation.HIDDEN,targetRelativePath) &&
            after.targetOwned==true && after.visibleInAndroid==false) {
            "Скрытие не подтверждено. Сохранён журнал для безопасного восстановления"
        }
        journal.transition(intent,SelectiveArchiveMovePhase.HIDDEN_VERIFIED)
    }

    suspend fun restore(
        checkpoint:SelectiveArchiveCheckpoint,
        original:SelectiveArchiveIdentity,
    ):SelectiveArchiveMoveReceipt=mutex.withLock {
        check(checkpoint.originals.singleOrNull { it.catalogId==original.catalogId }==original)
        val initial=checkNotNull(journal.read(checkpoint.folderId,original.catalogId)) {
            "Не найдена контрольная точка скрытого файла"
        }
        check(initial.originalUri==original.originalUri && initial.originalSha256==original.sha256)
        check(initial.phase in setOf(SelectiveArchiveMovePhase.HIDDEN_VERIFIED,
            SelectiveArchiveMovePhase.RESTORE_INTENT,SelectiveArchiveMovePhase.RESTORED_VERIFIED)) {
            "Нет проверенного скрытого файла или операция требует диагностики"
        }
        if(initial.phase==SelectiveArchiveMovePhase.RESTORED_VERIFIED) {
            val observed=port.observe(original,initial)
            check(verifySelectiveArchiveRestoration(original,observed.identity,
                observed.visibleInAndroid,observed.referencesUnchanged)==null &&
                observed.location==SelectiveArchiveLocation.SOURCE) {
                "Проверка уже восстановленного файла не пройдена"
            }
            return@withLock initial
        }
        val intent=if(initial.phase==SelectiveArchiveMovePhase.HIDDEN_VERIFIED)
            journal.transition(initial,SelectiveArchiveMovePhase.RESTORE_INTENT)
        else initial
        val state=port.observe(original,intent)
        when {
            locationIsExact(state,original,SelectiveArchiveLocation.HIDDEN,intent.targetRelativePath) &&
                state.targetOwned==true && state.visibleInAndroid==false -> {
                // No overwrite: the port must check original destination absent
                // before changing the existing same-ID MediaStore record.
                port.changeRelativePath(original,intent,toHidden=false)
            }
            locationIsExact(state,original,SelectiveArchiveLocation.SOURCE,intent.targetRelativePath) &&
                state.visibleInAndroid==true -> Unit // Crash after restore.
            else -> error("Восстановление заблокировано: исходная запись неизвестна")
        }
        val after=port.observe(original,intent)
        check(after.location==SelectiveArchiveLocation.SOURCE &&
            verifySelectiveArchiveRestoration(original,after.identity,
                after.visibleInAndroid,after.referencesUnchanged)==null) {
            "Оригинальный URI, содержимое, дата или связи не восстановлены"
        }
        journal.transition(intent,SelectiveArchiveMovePhase.RESTORED_VERIFIED)
    }
}
