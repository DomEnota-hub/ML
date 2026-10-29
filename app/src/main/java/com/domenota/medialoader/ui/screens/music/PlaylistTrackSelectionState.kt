package com.domenota.medialoader.ui.screens.music

data class PlaylistSelectionResult(val addedKeys: List<String>, val unavailableKeys: List<String>)

data class PlaylistTrackSelectionState(val selectedKeys: List<String> = emptyList()) {
    fun canonicalize(aliases: Map<String, String>) = copy(selectedKeys = selectedKeys.map { aliases[it] ?: it }.distinct())
    fun toggle(key: String, existing: Set<String>, aliases: Map<String, String> = emptyMap()): PlaylistTrackSelectionState {
        val canonical = aliases[key] ?: key
        val current = canonicalize(aliases)
        if (canonical in existing.map { aliases[it] ?: it }) return current
        return current.copy(selectedKeys = if (canonical in current.selectedKeys) current.selectedKeys - canonical else current.selectedKeys + canonical)
    }
    fun confirm(available: Set<String>, existing: Set<String>): PlaylistSelectionResult {
        val pending = selectedKeys.distinct().filterNot { it in existing }
        return PlaylistSelectionResult(pending.filter { it in available }, pending.filterNot { it in available })
    }
}
