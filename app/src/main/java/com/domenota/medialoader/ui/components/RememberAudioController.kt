package com.domenota.medialoader.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import com.domenota.medialoader.data.playback.AudioControllerConnection

/** Acquires after composition commits; leaving a screen removes its observer and lease only. */
@Composable
internal fun rememberAudioController(): MediaController? {
    val context = LocalContext.current.applicationContext
    var controller by remember(context) { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val lease = AudioControllerConnection.acquire(context)
        var active = true
        lease.future.addListener({
            if (active) controller = runCatching { lease.future.get() }.getOrNull()
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            active = false
            controller = null
            lease.release()
        }
    }
    return controller
}
