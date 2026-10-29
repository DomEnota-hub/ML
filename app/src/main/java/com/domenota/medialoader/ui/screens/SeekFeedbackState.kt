package com.domenota.medialoader.ui.screens

internal data class SeekFeedbackState(
    val message: String? = null,
    val revision: Int = 0,
) {
    fun show(deltaMs: Long): SeekFeedbackState = copy(
        message = if (deltaMs < 0L) "−10 с" else "+10 с",
        revision = revision + 1,
    )

    fun clearIfCurrent(expectedRevision: Int): SeekFeedbackState =
        if (revision == expectedRevision) copy(message = null) else this
}
