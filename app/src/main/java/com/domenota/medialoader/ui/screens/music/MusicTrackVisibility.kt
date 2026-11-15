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
    fun hidden(row: DownloadEntity, hiddenKeys: Set<String>): Boolean {
        if (hiddenKeys.isEmpty()) return false
        val id = encode(row.id)
        val uri = row.savedUri?.takeIf { it.isNotBlank() }?.let(::encode)
        return hiddenKeys.any { key ->
            val dot = key.indexOf('.')
            dot >= 0 && (key.substring(0, dot) == id || (uri != null && key.substring(dot + 1) == uri))
        }
    }
    /** Expand saved hidden identities once, rather than encoding every track for every
     * hidden record on each catalog recomposition. */
    internal fun blockedIdentities(hiddenKeys: Set<String>): Set<String> = buildSet {
        fun decode(value: String) = runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
        hiddenKeys.forEach { key ->
            val dot = key.indexOf('.')
            if (dot >= 0) {
                decode(key.substring(0, dot))?.takeIf { it.isNotBlank() }?.let(::add)
                decode(key.substring(dot + 1))?.takeIf { it.isNotBlank() }?.let { uri ->
                    addAll(com.domenota.medialoader.data.playback.PublicMediaIdentity.uriKeys(uri))
                }
            }
        }
    }
    fun update(current: Set<String>, row: DownloadEntity, hide: Boolean): Set<String> {
        val remaining = current.filterNot { matches(it, row) }.toSet()
        return if (hide) remaining + record(row) else remaining
    }
}
