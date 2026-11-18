package com.domenota.medialoader.data.storage

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.io.File
import org.json.JSONObject
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
    @Volatile private var folderOperation = false
    fun isFolderVisibilityOperation(): Boolean = running.value && folderOperation
    private val completedVersion = MutableStateFlow(0L)
    private val folderVisibilityPending = MutableStateFlow(false)
    private val editorHolds = java.util.WeakHashMap<Any, Boolean>()
    // The private vault can publish a distinct, collision-protected public
    // destination even while an unrelated folder SHOW journal is pending.
    // Hold this gate until private publication AND its date proof complete.
    private var privateRestorePublicationActive = false
    val isRunning: StateFlow<Boolean> = running.asStateFlow()
    val version: StateFlow<Long> = completedVersion.asStateFlow()

    /** Automatic alias maintenance must also pause BETWEEN resume attempts and
     * after process restarts, when an interrupted cross-store intent still exists. */
    fun hasPendingJournal(context: Context): Boolean {
        return running.value || hasPendingHistoricalJournal(context) || OldDownloadsRecovery.pending(context) ||
            com.domenota.medialoader.data.catalog.visibility.FolderVisibilityManager.hasPendingJournal(context)
    }

    internal fun hasPendingHistoricalJournal(context: Context): Boolean {
        val file = File(context.noBackupFilesDir, "historical-media-recovery-v1/operation.json")
        if (File(file.path + ".bak").exists()) return true
        if (!file.exists()) return false
        return try {
            // A status read must never invoke AtomicFile recovery while another
            // writer may be committing a checkpoint.
            !JSONObject(file.readText()).getBoolean("done")
        } catch (_: Exception) { true } // Unknown journal: defer automatic writes.
    }

    @Synchronized fun begin() {
        check(!folderVisibilityPending.value) { "Сначала завершите перенос скрытой папки" }
        beginExclusive()
    }
    @Synchronized fun beginFolderVisibility() = beginExclusive(folder = true)
    @Synchronized fun markFolderVisibilityPending(pending: Boolean) { folderVisibilityPending.value = pending }
    private fun beginExclusive(folder: Boolean = false) {
        check(!running.value && !privateRestorePublicationActive) { "Восстановление файлов уже выполняется" }
        check(editorHolds.isEmpty()) { "Закройте редактор перед восстановлением файлов" }
        folderOperation = folder
        running.value = true
        BackgroundMediaWork.setBusy("historical-recovery", true)
    }

    /** Only a confirmed catalog mutation advances the media-generation counter.
     * A rejected first binding proof has not changed any public alias or index. */
    @Synchronized fun end(changed: Boolean = true) {
        check(running.value) { "Восстановление файлов не запущено" }
        if (changed) completedVersion.value += 1L
        running.value = false
        folderOperation = false
        BackgroundMediaWork.setBusy("historical-recovery", false)
    }

    /** Unlike a general mutation, a verified private restore does not touch an
     * existing hidden source, receipt or catalog binding. No concurrent file
     * mutation is allowed and historic recovery must still be idle. Exact
     * pending SHOW filenames are reserved separately by FolderVisibilityManager. */
    @Synchronized fun beginIndependentPrivatePublication(context: Context) {
        check(!running.value && !privateRestorePublicationActive) { "Другая файловая операция выполняется" }
        check(!hasPendingHistoricalJournal(context) && !OldDownloadsRecovery.pending(context)) {
            "Сначала завершите восстановление исторических файлов"
        }
        privateRestorePublicationActive = true
    }

    @Synchronized fun requireIndependentPrivatePublication() {
        check(privateRestorePublicationActive && !running.value) {
            "Не подтверждена независимая операция восстановления из Личного"
        }
    }

    @Synchronized fun endIndependentPrivatePublication() {
        check(privateRestorePublicationActive) { "Восстановление из Личного не выполняется" }
        privateRestorePublicationActive = false
    }

    fun requireIdle() {
        check(!running.value && !folderVisibilityPending.value && !privateRestorePublicationActive) {
            "Дождитесь завершения восстановления или переноса файлов"
        }
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
