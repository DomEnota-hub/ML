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
    // Immutable read-only snapshot: Compose and media observers must never block on
    // shard IO held under the shared privacy monitor. Mutations publish fail-closed
    // identity state before any disk writes or heavy invalidation.
    private data class Snapshot(
        val generation: Long, val blockedIds: Set<String>, val aliases: Set<String>,
    ) {
        fun accepts(requestEpoch: Long, id: String, identities: Set<String>): Boolean =
            requestEpoch == generation && id !in blockedIds && identities.none { it in aliases }
    }
    @Volatile private var published: Snapshot? = null
    private fun publish(value: DuplicatePrivacyEpoch) {
        published = Snapshot(value.generation, value.blockedIdsSnapshot(), value.blockedAliasesSnapshot())
    }
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir,"duplicate-privacy-epoch"))
    private fun state(context: Context): DuplicatePrivacyEpoch {
        return epoch ?: DuplicatePrivacyEpoch(if (file(context).baseFile.exists()) runCatching { file(context).openRead().bufferedReader().use { it.readText().toLong() } }
            .getOrDefault(Long.MIN_VALUE) else 0).also { epoch = it; publish(it); mutableChanges.value = it.generation }
    }
    fun currentBlockedKeys(): Set<String> {
        val value = published ?: return synchronized(lock) { epoch?.blockedKeys().orEmpty() }
        return value.blockedIds + value.aliases
    }
    fun generation(context: Context): Long =
        (published ?: synchronized(lock) { state(context); requireNotNull(published) }).generation
    fun accepts(context: Context, generation: Long, id: String, identities: Set<String> = emptySet()): Boolean =
        (published ?: synchronized(lock) { state(context); requireNotNull(published) }).accepts(generation,id,identities)
    suspend fun revoke(context: Context, id: String, identities: Set<String> = emptySet()) = withContext(Dispatchers.IO + NonCancellable) {
        synchronized(lock) {
            val state = state(context)
            // Full invalidation remains the default. Only a verified, idle,
            // unrelated move can keep completed and partial search reports.
            // This proof runs under the shared persistence lock, so no report
            // writer can change the material being classified.
            val keepUnrelatedReports = DuplicatePrivacyRetention.provenUnrelated(context,id,identities)
            if (keepUnrelatedReports) state.revokeUnrelated(id,identities)
            else state.revoke(id,identities)
            // Publish the block before any file I/O or expensive cache removal.
            publish(state)
            com.domenota.medialoader.core.logging.AppLog.i(
                "DuplicatePrivacy",
                if (keepUnrelatedReports) "PRIVATE_MOVE · unrelated reports retained"
                else "PRIVATE_MOVE · strict report invalidation",
            )
            if (!keepUnrelatedReports) {
                mutableChanges.value = state.generation
                val target = file(context); val stream = target.startWrite()
                try { stream.write(state.generation.toString().toByteArray()); target.finishWrite(stream) }
                catch (error: Throwable) { target.failWrite(stream); throw error }
            }
            // Computed caches may contain arbitrary media references even when
            // the saved results do not. Always clear them; never retain PCM.
            MusicComparisonFrames.clearForPrivacy(context)
            MusicPairResultCache.clearForPrivacy(context)
            MusicDuplicateScanner.clearForPrivacy(context)
            VisualDuplicateScanner.clearForPrivacy(context)
            com.domenota.medialoader.data.recognition.TrackRecognitionCache.clearForPrivacy(context)
            if (!keepUnrelatedReports) {
                MusicDuplicateSessionStore.clearForPrivacy(context)
                DuplicateBackgroundScan.invalidateForPrivacy()
                VisualDuplicateSessionStore.clearForPrivacy(context)
                VisualDuplicateBackgroundScan.invalidateForPrivacy()
            }
        }
        PersistentProjectionCache.clearAll(context)
        com.domenota.medialoader.data.playback.ListeningHistoryStore.get(context).clearDerivedProjections()
        context.stopService(Intent(context,MusicDuplicateScanService::class.java))
        context.stopService(Intent(context,VisualDuplicateScanService::class.java))
    }
    fun restored(context: Context, id: String) = synchronized(lock) {
        val current = state(context)
        current.restored(id)
        publish(current)
    }
}
