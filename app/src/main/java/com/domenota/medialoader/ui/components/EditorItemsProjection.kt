package com.domenota.medialoader.ui.components

import androidx.compose.runtime.*
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.toUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

private class InputIdentity(val rows: List<DownloadEntity>) {
    override fun equals(other: Any?) = other is InputIdentity && rows === other.rows
    override fun hashCode() = System.identityHashCode(rows)
}
private data class Projection(val source: List<DownloadEntity>, val aliases: Map<String, String>, val rows: List<DownloadUiItem>)

/** Alias rows remain available to old routes; the picker shows one logical file. */
internal fun editorUniqueFiles(rows: List<DownloadEntity>, aliases: Map<String, String>): List<DownloadEntity> {
    val seenIds = HashSet<String>()
    val seenUris = HashSet<String>()
    return rows.filter { row ->
        if (row.hidden || row.state != DownloadState.COMPLETED || row.savedUri.isNullOrBlank()) false
        else {
            val id = aliases[row.id] ?: row.id
            val duplicate = id in seenIds || row.savedUri in seenUris
            seenIds.add(id)
            seenUris.add(row.savedUri)
            !duplicate
        }
    }
}

@Composable
internal fun rememberEditorItems(rows: List<DownloadEntity>, aliases: Map<String, String> = emptyMap()): List<DownloadUiItem> {
    val result = remember { mutableStateOf<Projection?>(null) }
    LaunchedEffect(InputIdentity(rows), aliases) {
        result.value = withContext(Dispatchers.Default) {
            Projection(rows, aliases, editorUniqueFiles(rows, aliases).mapNotNull { row ->
                coroutineContext.ensureActive()
                if (row.state == DownloadState.COMPLETED && row.savedUri != null) row.toUi() else null
            })
        }
    }
    val snapshot = result.value ?: return emptyList()
    if (snapshot.source === rows && snapshot.aliases == aliases) return snapshot.rows

    // Refreshes of a large MediaStore library replace List instances often.
    // A new background projection may not be ready for a frame, but an empty
    // result here visibly flips the picker from 212 videos to 0 and back.
    // Keep only entries still present in the CURRENT authorized inputs: this
    // fails closed for deleted/hidden/private/permission-revoked rows.
    val currentAllowed = remember(InputIdentity(rows)) {
        rows.asSequence()
            .filter { !it.hidden && it.state == DownloadState.COMPLETED &&
                !it.savedUri.isNullOrBlank() }
            .associate { it.id to it.savedUri }
    }
    return remember(snapshot, currentAllowed) {
        snapshot.rows.filter { row -> currentAllowed[row.id] == row.savedUri }
    }
}
