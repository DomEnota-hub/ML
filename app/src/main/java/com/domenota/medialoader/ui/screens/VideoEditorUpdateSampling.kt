package com.domenota.medialoader.ui.screens

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.sample

/** Debouncing a continuously dragged layer freezes preview until the finger stops. */
@OptIn(FlowPreview::class)
internal fun <T> Flow<T>.sampleVideoEditorUpdates(): Flow<T> = sample(50L)
