package com.domenota.medialoader.data

import com.domenota.medialoader.data.storage.SavedFileStatus
import java.io.IOException

/** Metadata survives an inaccessible file, provider refusal, or interrupted deletion. */
internal suspend fun permanentlyDeleteSavedMedia(
    status: SavedFileStatus,
    deleteFile: suspend () -> Boolean,
    deleteRecord: suspend () -> Unit,
) {
    when (status) {
        SavedFileStatus.PRESENT -> if (!deleteFile()) throw IOException("Не удалось удалить файл; запись сохранена")
        SavedFileStatus.MISSING -> Unit // Includes retry after system MediaStore consent deleted the bytes.
        SavedFileStatus.INACCESSIBLE, SavedFileStatus.UNCHECKED ->
            throw IOException("Нет доступа к файлу; запись сохранена")
    }
    deleteRecord()
}
