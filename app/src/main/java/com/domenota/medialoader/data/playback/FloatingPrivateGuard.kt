package com.domenota.medialoader.data.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile

/** Wait for database visibility before showing an overlay; revoke it as soon as the file moves. */
internal suspend fun guardFloatingPlayback(
    id: String,
    privateUri: Boolean,
    privateIds: Flow<Set<String>>,
    open: () -> Unit,
    close: () -> Unit,
) {
    if (privateUri) { close(); return }
    var opened = false
    privateIds.takeWhile { ids ->
        if (id in ids) { close(); false } else true
    }.collect {
        if (!opened) { open(); opened = true }
    }
}
