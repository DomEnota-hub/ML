package com.domenota.medialoader.data.storage

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.domenota.medialoader.data.playback.AudioPlaybackService
import com.domenota.medialoader.data.playback.BackgroundMediaWork
import com.domenota.medialoader.data.playback.FloatingVideoService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Process-local coordination for public media replacement and rebuildable library state. */
internal object HistoricalRecoverySignals {
    private val running = MutableStateFlow(false)
    private val completedVersion = MutableStateFlow(0L)
    private val editorHolds = java.util.WeakHashMap<Any, Boolean>()
    val isRunning: StateFlow<Boolean> = running.asStateFlow()
    val version: StateFlow<Long> = completedVersion.asStateFlow()

    @Synchronized fun begin() {
        check(!running.value) { "Восстановление файлов уже выполняется" }
        check(editorHolds.isEmpty()) { "Закройте редактор перед восстановлением файлов" }
        running.value = true
        BackgroundMediaWork.setBusy("historical-recovery", true)
    }

    @Synchronized fun end() {
        check(running.value) { "Восстановление файлов не запущено" }
        completedVersion.value += 1L
        running.value = false
        BackgroundMediaWork.setBusy("historical-recovery", false)
    }

    fun requireIdle() {
        check(!running.value) { "Дождитесь завершения восстановления файлов" }
    }

    fun <T> normalMutation(block: () -> T): T = synchronized(this) {
        requireIdle()
        block()
    }

    @Synchronized fun setEditorHold(owner: Any, held: Boolean) {
        if (held) editorHolds[owner] = true else editorHolds.remove(owner)
    }
}

/** Release public playback before changing its URI references, including paused queues. */
@Suppress("DEPRECATION")
internal suspend fun ensureHistoricalPlaybackIdle(context: Context): Unit = withContext(Dispatchers.Main.immediate) {
    val application = context.applicationContext
    val activities = application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: error("Не удалось проверить воспроизведение")
    FloatingVideoService.releaseHistoricalPlayback()
    application.stopService(Intent(application, FloatingVideoService::class.java))
    withTimeout(5_000L) {
        // Let onDestroy save the final position and release the player on this main looper.
        delay(100L)
        while (activities.getRunningServices(Int.MAX_VALUE).any {
            it.service == ComponentName(application, FloatingVideoService::class.java)
        }) delay(50L)
    }
    AudioPlaybackService.clearHistoricalPlayback()
}
