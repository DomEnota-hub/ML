package com.domenota.medialoader.ui.components

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay

/** No second decode for a detailed cache hit; cancellation cannot publish an obsolete refinement. */
internal suspend fun <T : Any> progressivePreview(cached: T?, quick: suspend () -> T?,
    detailed: suspend () -> T?, publish: (T) -> Unit) {
    if (cached != null) { publish(cached); return }
    quick()?.let { currentCoroutineContext().ensureActive(); publish(it) }
    delay(120)
    detailed()?.let { currentCoroutineContext().ensureActive(); publish(it) }
}
