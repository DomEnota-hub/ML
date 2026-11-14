package com.domenota.medialoader.data.library

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.asStateFlow

/** All public duplicate persistence shares this monitor with the private-move boundary. */
internal object DuplicatePrivacyGuard {
    val lock = Any()
    private val mutableChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()
    private var epoch: DuplicatePrivacyEpoch? = null
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir,"duplicate-privacy-epoch"))
    private fun state(context: Context): DuplicatePrivacyEpoch {
        return epoch ?: DuplicatePrivacyEpoch(if (file(context).baseFile.exists()) runCatching { file(context).openRead().bufferedReader().use { it.readText().toLong() } }
            .getOrDefault(Long.MIN_VALUE) else 0).also { epoch = it; mutableChanges.value = it.generation }
    }
    fun generation(context: Context): Long = synchronized(lock) { state(context).generation }
    fun accepts(context: Context, generation: Long, id: String, identities: Set<String> = emptySet()): Boolean = synchronized(lock) { state(context).accepts(generation,id,identities) }
    suspend fun revoke(context: Context, id: String, identities: Set<String> = emptySet()) = withContext(Dispatchers.IO + NonCancellable) {
        synchronized(lock) {
            val state = state(context)
            state.revoke(id,identities)
            mutableChanges.value = state.generation
            val target = file(context); val stream = target.startWrite()
            try { stream.write(state.generation.toString().toByteArray()); target.finishWrite(stream) }
            catch (error: Throwable) { target.failWrite(stream); throw error }
            // Invalidation removes only our reports/checkpoints; never user media or unrelated caches.
            MusicDuplicateScanner.clearForPrivacy(context)
            MusicDuplicateSessionStore.clearForPrivacy(context)
            DuplicateBackgroundScan.invalidateForPrivacy()
        }
        context.stopService(Intent(context,MusicDuplicateScanService::class.java))
    }
    fun restored(context: Context, id: String) = synchronized(lock) { state(context).restored(id) }
}
