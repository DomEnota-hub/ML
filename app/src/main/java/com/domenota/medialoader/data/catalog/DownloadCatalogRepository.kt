package com.domenota.medialoader.data.catalog

import androidx.room.withTransaction
import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import com.domenota.medialoader.core.database.CatalogFolderEntity
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.model.DownloadState
import java.util.Locale
import java.util.UUID

/** Logical organization only; no operation here reads, moves, or deletes saved media. */
class DownloadCatalogRepository(private val database: HistoryDatabase) {
    private val dao = database.catalogDao()
    val folders = dao.observeFolders()
    val assignments = dao.observeAssignments()

    suspend fun createFolder(rawName: String): String = database.withTransaction {
        val name = CatalogNames.normalize(rawName)
        val key = CatalogNames.key(name)
        require(dao.folderByKey(key) == null) { "Папка с таким именем уже есть" }
        val id = UUID.randomUUID().toString()
        dao.insertFolder(CatalogFolderEntity(id, name, key, System.currentTimeMillis()))
        id
    }

    suspend fun renameFolder(id: String, rawName: String) = database.withTransaction {
        require(dao.folderById(id) != null) { "Папка не найдена" }
        val name = CatalogNames.normalize(rawName)
        val key = CatalogNames.key(name)
        require(dao.folderByKey(key)?.id.let { it == null || it == id }) {
            "Папка с таким именем уже есть"
        }
        dao.renameFolder(id, name, key)
    }

    suspend fun deleteFolder(id: String) = database.withTransaction {
        // ON DELETE SET NULL keeps every favorite and every download record.
        dao.deleteFolder(id)
    }

    suspend fun setFolder(downloadId: String, folderId: String?) = database.withTransaction {
        requireCompleted(downloadId)
        require(folderId == null || dao.folderById(folderId) != null) { "Папка не найдена" }
        val previous = dao.assignmentById(downloadId)
        if (folderId == null && previous?.isFavorite != true) dao.removeAssignment(downloadId)
        else dao.saveAssignment(CatalogAssignmentEntity(downloadId, folderId,
            previous?.isFavorite == true))
    }

    suspend fun toggleFavorite(downloadId: String) = database.withTransaction {
        requireCompleted(downloadId)
        val previous = dao.assignmentById(downloadId)
        val favorite = previous?.isFavorite != true
        if (!favorite && previous?.folderId == null) dao.removeAssignment(downloadId)
        else dao.saveAssignment(CatalogAssignmentEntity(downloadId, previous?.folderId, favorite))
    }

    suspend fun setFavorite(downloadId: String, favorite: Boolean) = database.withTransaction {
        requireCompleted(downloadId)
        val previous = dao.assignmentById(downloadId)
        if (!favorite && previous?.folderId == null) dao.removeAssignment(downloadId)
        else dao.saveAssignment(CatalogAssignmentEntity(downloadId, previous?.folderId, favorite))
    }

    private suspend fun requireCompleted(id: String) {
        require(database.downloadDao().byId(id)?.let {
            it.state == DownloadState.COMPLETED && !it.hidden
        } == true) { "Файл недоступен в истории загрузок" }
    }
}

object CatalogNames {
    fun normalize(raw: String): String {
        val name = raw.trim().replace(Regex("\\s+"), " ")
        require(name.isNotEmpty()) { "Введите название папки" }
        require(name.length <= 48 && name.none(Char::isISOControl)) {
            "Название папки должно содержать не более 48 символов"
        }
        return name
    }

    fun key(name: String): String = name.lowercase(Locale.ROOT)
}
