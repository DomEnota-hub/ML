package com.domenota.medialoader.data.privatevault

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** File/database ordering is explicit; callers serialize these operations with file mutations. */
class PrivateMediaOperations(
    private val requireUnlocked: () -> Unit,
    private val read: suspend (String) -> DownloadEntity?,
    private val save: suspend (DownloadEntity) -> Unit,
    private val deleteRow: suspend (String) -> Unit,
    private val importCopy: suspend (String, String) -> String,
    private val removePrivate: suspend (String) -> Boolean,
    private val removePublic: suspend (String) -> Boolean,
    private val publish: suspend (DownloadEntity) -> Pair<String, String>,
    private val requireConfigured: () -> Unit = requireUnlocked,
) {
    suspend fun move(id: String) {
        requireConfigured()
        val row = read(id) ?: error("Запись не найдена")
        if (row.isPrivate) { finishMove(id); return }
        require(!row.hidden && row.state == DownloadState.COMPLETED) { "Дождитесь завершения загрузки" }
        val source = requireNotNull(row.savedUri) { "Файл отсутствует" }
        val extension = row.originalName.substringAfterLast('.', "bin").takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) } ?: "bin"
        val uri = importCopy("${UUID.randomUUID()}.$extension", source)
        withContext(NonCancellable) {
            val privateRow = row.copy(isPrivate = true, savedUri = uri, privateSourceUri = source)
            try { save(privateRow) }
            catch (error: Throwable) { runCatching { removePrivate(uri) }; throw error }
            cleanupPublicSource(privateRow)
        }
    }

    suspend fun finishMove(id: String) {
        requireUnlocked()
        val row = read(id) ?: error("Запись не найдена")
        require(row.isPrivate)
        cleanupPublicSource(row)
    }

    /** Only the newly appended row may reach cleanup without private read authorization. */
    private suspend fun cleanupPublicSource(row: DownloadEntity) {
        val source = row.privateSourceUri ?: return
        withContext(NonCancellable) {
            if (!removePublic(source)) throw IOException("Приватная копия сохранена, но публичный файл не удалён. Повторите завершение переноса.")
            save(row.copy(privateSourceUri = null))
        }
    }

    suspend fun restore(id: String) {
        requireUnlocked()
        val row = read(id) ?: error("Запись не найдена")
        require(row.isPrivate)
        require(row.privateSourceUri == null) { "Сначала завершите перенос: публичная копия ещё существует" }
        withContext(NonCancellable) {
            val (name, uri) = publish(row)
            try { save(row.copy(originalName = name, savedUri = uri, isPrivate = false, privateSourceUri = null)) }
            catch (error: Throwable) { runCatching { removePublic(uri) }; throw error }
            if (!removePrivate(requireNotNull(row.savedUri))) throw IOException("Файл восстановлен; не удалось удалить старую приватную копию")
        }
    }

    suspend fun delete(id: String) {
        requireUnlocked()
        val row = read(id) ?: return
        require(row.isPrivate)
        require(row.privateSourceUri == null) { "Сначала завершите перенос: публичная копия ещё существует" }
        withContext(NonCancellable) {
            if (!removePrivate(requireNotNull(row.savedUri))) throw IOException("Не удалось удалить приватный файл")
            deleteRow(id)
        }
    }
}
