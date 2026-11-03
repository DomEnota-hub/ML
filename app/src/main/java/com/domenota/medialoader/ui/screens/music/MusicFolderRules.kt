package com.domenota.medialoader.ui.screens.music

import com.domenota.medialoader.core.database.DownloadEntity

internal object MusicFolderRules {
    fun title(key: String) = key.substringAfter(':').trimEnd('/').ifBlank { "Корень хранилища" }
    fun key(row: DownloadEntity, deviceFolders: Map<String, String>): String? =
        row.savedUri?.let(deviceFolders::get)?.takeIf { it.isNotBlank() }
            ?: row.groupId?.takeIf { row.providerId == "device" && it.isNotBlank() }
            ?: row.savedUri?.takeIf { it.startsWith("file://") }?.removePrefix("file://")?.substringBeforeLast('/')
    fun visible(row: DownloadEntity, excluded: Set<String>, deviceFolders: Map<String, String>) =
        key(row, deviceFolders) !in excluded
}
