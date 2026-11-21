package com.domenota.medialoader.data.library

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.domenota.medialoader.core.logging.AppLog
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

/** Two bounded codec workers; no pair-dependent cache identity or PCM persistence. */
internal object MusicComparisonFrames {
    private val codecs = Semaphore(2)
    // Process lifetime, bounded by the store; all access guarded by the privacy lock.
    private var store: ComparisonFrameStore? = null
    private fun directory(context: Context) = File(context.noBackupFilesDir, "duplicate-comparison-frames-v1")
    private fun store(context: Context): ComparisonFrameStore =
        store ?: ComparisonFrameStore(directory(context)).also { store = it }

    suspend fun load(context: Context, track: DuplicateTrack, before: Pair<Long, Long?>,
        stepMs: Long): List<ComparisonFrame>? {
        val started = SystemClock.elapsedRealtime()
        val key = ComparisonFrameKey.verified(track, before, stepMs)
        fun allowed() = DuplicatePrivacyGuard.accepts(context, track.privacyEpoch, track.id)
        fun cached(): List<ComparisonFrame>? = synchronized(DuplicatePrivacyGuard.lock) {
            if (key != null && allowed()) store(context).read(key) else null
        }
        coroutineContext.ensureActive()
        if (!allowed()) return null
        cached()?.let {
            if (!allowed() || MusicDuplicateScanner.comparisonStamp(context, track) != before) return null
            AppLog.i("MusicCompare", "frames cache count=${it.size} stepMs=$stepMs elapsedMs=${SystemClock.elapsedRealtime()-started}")
            return it
        }
        return codecs.withPermit {
            coroutineContext.ensureActive()
            if (!allowed()) return@withPermit null
            cached()?.let {
                if (!allowed() || MusicDuplicateScanner.comparisonStamp(context, track) != before) return@withPermit null
                AppLog.i("MusicCompare", "frames cache count=${it.size} stepMs=$stepMs elapsedMs=${SystemClock.elapsedRealtime()-started}")
                return@withPermit it
            }
            val frames = AcousticAudioDecoder.comparisonFrames(context, Uri.parse(track.uri), stepMs)
                ?: return@withPermit null
            coroutineContext.ensureActive()
            if (MusicDuplicateScanner.comparisonStamp(context, track) != before || !allowed()) return@withPermit null
            val published = synchronized(DuplicatePrivacyGuard.lock) {
                if (!allowed()) false else {
                    if (key != null) store(context).write(key, frames)
                    true
                }
            }
            if (!published || !allowed()) return@withPermit null
            AppLog.i("MusicCompare", "frames decoded count=${frames.size} stepMs=$stepMs elapsedMs=${SystemClock.elapsedRealtime()-started}")
            frames
        }
    }

    /** Must run under DuplicatePrivacyGuard.lock together with epoch invalidation. */
    fun clearForPrivacy(context: Context) {
        val removed = store?.clear() ?: directory(context).let { !it.exists() || it.deleteRecursively() }
        store = null
        check(removed) { "Не удалось очистить кеш сравнения музыки" }
    }
}
