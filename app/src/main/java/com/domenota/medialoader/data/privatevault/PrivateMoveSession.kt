package com.domenota.medialoader.data.privatevault

import com.domenota.medialoader.data.catalog.FileBatchResult
import com.domenota.medialoader.data.catalog.runFileBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PrivateMoveStatus(val busy: Boolean = false, val result: FileBatchResult? = null, val revision: Long = 0L)

/** Owned by the ViewModel, independently of any screen's collection/composition lifecycle. */
class PrivateMoveSession(private val scope: CoroutineScope, private val move: suspend (String) -> Unit) {
    private val mutableState = MutableStateFlow(PrivateMoveStatus())
    val state = mutableState.asStateFlow()
    fun start(ids: List<String>): Boolean {
        if (mutableState.value.busy || ids.isEmpty()) return false
        mutableState.value = mutableState.value.copy(busy = true, result = null)
        scope.launch {
            try {
                val result = runFileBatch(ids, move)
                mutableState.value = mutableState.value.copy(result = result, revision = mutableState.value.revision + 1)
            } finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
        return true
    }
    fun consume(revision: Long) {
        if (mutableState.value.revision == revision) mutableState.value = mutableState.value.copy(result = null)
    }
}
