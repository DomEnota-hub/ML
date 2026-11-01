package com.domenota.medialoader.ui.screens

import kotlinx.coroutines.CancellationException

/**
 * Compose cancels obsolete preview work when a tool/tab/value changes.
 * Cancellation is expected, never a failure to open a watermark.
 */
internal fun photoPreviewFailureMessage(error: Exception): String? = when (error) {
    is CancellationException -> null
    else -> "Не удалось обновить предпросмотр. Попробуйте изменить параметр ещё раз."
}
