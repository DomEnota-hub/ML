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
private data class Projection(val source: List<DownloadEntity>, val rows: List<DownloadUiItem>)

@Composable
internal fun rememberEditorItems(rows: List<DownloadEntity>): List<DownloadUiItem> {
    val result by produceState<Projection?>(null, InputIdentity(rows)) {
        value = withContext(Dispatchers.Default) {
            Projection(rows, rows.mapNotNull { row ->
                coroutineContext.ensureActive()
                if (row.state == DownloadState.COMPLETED && row.savedUri != null) row.toUi() else null
            })
        }
    }
    // Never show a previous input's private rows after permissions/lock state changes.
    return result?.takeIf { it.source === rows }?.rows.orEmpty()
}
