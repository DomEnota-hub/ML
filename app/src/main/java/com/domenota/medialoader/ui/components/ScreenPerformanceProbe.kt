package com.domenota.medialoader.ui.components

import androidx.compose.runtime.*
import com.domenota.medialoader.core.logging.PerformanceLog

/** First Compose frame is explicitly separate from data/decoder readiness. */
@Composable
internal fun ScreenPerformanceProbe(screen: String, ready: Boolean) {
    val started = remember(screen) { PerformanceLog.begin("screen_enter:$screen") }
    var readinessReported by remember(screen) { mutableStateOf(false) }
    SideEffect { PerformanceLog.screen(screen) }
    LaunchedEffect(screen) {
        withFrameNanos { }
        PerformanceLog.end("screen_first_compose_frame:$screen", started)
    }
    LaunchedEffect(screen, ready) {
        if (ready && !readinessReported) {
            readinessReported = true
            PerformanceLog.end("screen_data_ready:$screen", started)
        }
    }
}
