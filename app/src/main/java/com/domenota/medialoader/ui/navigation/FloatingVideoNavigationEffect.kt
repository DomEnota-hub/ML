package com.domenota.medialoader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.domenota.medialoader.data.playback.FloatingVideoHandoffRequest
import com.domenota.medialoader.data.playback.FloatingVideoService
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** An explicit activity action alone requests this route; never ordinary navigation. */
@Composable
internal fun FloatingVideoNavigationEffect(
    token: String?,
    candidateReady: (FloatingVideoHandoffRequest) -> Boolean,
    refresh: () -> Unit,
    open: (FloatingVideoHandoffRequest) -> Unit,
    consumed: (String) -> Unit,
    unavailable: () -> Unit,
) {
    val ready by rememberUpdatedState(candidateReady)
    val refreshCurrent by rememberUpdatedState(refresh)
    val openCurrent by rememberUpdatedState(open)
    val consumeCurrent by rememberUpdatedState(consumed)
    val unavailableCurrent by rememberUpdatedState(unavailable)
    LaunchedEffect(token) {
        val requestedToken = token ?: return@LaunchedEffect
        val request = FloatingVideoService.pendingHandoff(requestedToken)
        if (request == null) { consumeCurrent(requestedToken); return@LaunchedEffect }
        if (!ready(request)) refreshCurrent()
        val admitted = withTimeoutOrNull(12_000L) {
            while (FloatingVideoService.pendingHandoff(requestedToken) == request) {
                if (ready(request)) return@withTimeoutOrNull true
                delay(100L)
            }
            false
        } == true
        if (admitted) openCurrent(request)
        else {
            FloatingVideoService.cancelHandoff(requestedToken)
            unavailableCurrent()
        }
        consumeCurrent(requestedToken)
    }
}
