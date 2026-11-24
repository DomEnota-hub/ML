package com.domenota.medialoader.ui.components

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.domenota.medialoader.core.logging.AppLog

/**
 * Observe actual button availability without changing its admission policy.
 * Only fixed screen/action/reason tokens are allowed at call sites; never media IDs,
 * filenames, paths or URIs. Effects record intervals, not every recomposition.
 */
@Composable
internal fun DuplicateActionTrace(
    screen: String,
    action: String,
    enabled: Boolean,
    reason: String,
) {
    DisposableEffect(screen, action, enabled, reason) {
        val started = SystemClock.elapsedRealtime()
        val interval = AppLog.newOperationId("duplicate-ui")
        val state = if (enabled) "enabled" else "disabled"
        AppLog.i("DuplicateUI", "event=enter screen=$screen action=$action state=$state reason=$reason interval=$interval")
        onDispose {
            val duration = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            AppLog.i("DuplicateUI", "event=leave screen=$screen action=$action state=$state reason=$reason interval=$interval durationMs=$duration")
        }
    }
}
