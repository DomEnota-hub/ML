package com.domenota.medialoader.ui.screens.music

import com.domenota.medialoader.core.database.DownloadEntity
import java.util.Base64

/** Store identity aliases together so restoring a rescanned track clears every alias. */
internal object MusicTrackVisibility {
    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun record(row: DownloadEntity) = encode(row.id) + "." + encode(row.savedUri.orEmpty())
    private fun matches(record: String, row: DownloadEntity): Boolean {
        val aliases = record.split('.', limit = 2)
        return aliases.size == 2 && (aliases[0] == encode(row.id) ||
            (!row.savedUri.isNullOrBlank() && aliases[1] == encode(row.savedUri)))
    }
    fun hidden(row: DownloadEntity, hiddenKeys: Set<String>) = hiddenKeys.any { matches(it, row) }
    fun update(current: Set<String>, row: DownloadEntity, hide: Boolean): Set<String> {
        val remaining = current.filterNot { matches(it, row) }.toSet()
        return if (hide) remaining + record(row) else remaining
    }
}
