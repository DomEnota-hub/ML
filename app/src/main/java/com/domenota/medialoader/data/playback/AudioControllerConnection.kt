package com.domenota.medialoader.data.playback

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*

/** A single service connection for mini/full player surfaces.
 * Player calls/listeners/releases stay on main. Builder's synchronous bindService runs on IO;
 * setApplicationLooper is explicit because buildAsync's caller has no Looper there.
 * A short idle lease covers route transitions and Activity recreation without owning an Activity. */
internal class AudioControllerConnections(
    private val create: (Context) -> ListenableFuture<MediaController> = { buildAudioControllerAsync(it) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private class Entry(val future: ListenableFuture<MediaController>) {
        var users = 0
        var release: Job? = null
    }
    private var current: Entry? = null
    internal class Lease internal constructor(val future: ListenableFuture<MediaController>, private val close: () -> Unit) {
        private var closed = false
        fun release() { if (!closed) { closed = true; close() } }
    }
    fun acquire(context: Context): Lease {
        check(Looper.myLooper() == Looper.getMainLooper())
        val entry = current?.takeIf { existing ->
            !existing.future.isDone || runCatching { existing.future.get().isConnected }.getOrDefault(false)
        } ?: Entry(create(context)).also { current = it }

        entry.release?.cancel()
        entry.users++
        return Lease(entry.future) {
            check(Looper.myLooper() == Looper.getMainLooper())
            entry.users--
            if (entry.users == 0) entry.release = scope.launch {
                delay(5_000)
                if (entry.users == 0) {
                    if (current === entry) current = null
                    MediaController.releaseFuture(entry.future)
                }
            }
        }
    }
}

internal object AudioControllerConnection {
    private val connections = AudioControllerConnections()
    fun acquire(context: Context) = connections.acquire(context)
}

/** Construction may bind synchronously, even though Media3 returns a future. */
internal fun buildAudioControllerAsync(context: Context, listener: MediaController.Listener? = null): ListenableFuture<MediaController> {
    val app = context.applicationContext
    return asyncPlaybackConnection(release = { MediaController.releaseFuture(it) }) {
        val builder = MediaController.Builder(app,
            SessionToken(app, ComponentName(app, AudioPlaybackService::class.java)))
            .setApplicationLooper(Looper.getMainLooper())
        if (listener != null) builder.setListener(listener)
        builder.buildAsync()
    }
}
