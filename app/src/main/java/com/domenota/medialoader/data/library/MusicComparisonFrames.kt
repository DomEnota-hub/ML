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
    // At most two temporary PCM streams (~38.5 MB of float chunks total).
    // Cancellation of a pair releases its ownership before another pair enters.
    private val pairPermit=Semaphore(1)
    suspend fun <T> withPair(block:suspend ()->T):T=pairPermit.withPermit{block()}
    // Process lifetime, bounded by the store; all access guarded by the privacy lock.
    private var store: ComparisonFrameStore? = null
    private fun directory(context: Context) = File(context.noBackupFilesDir, "duplicate-comparison-frames-v1")
    private fun store(context: Context): ComparisonFrameStore =
        store ?: ComparisonFrameStore(directory(context)).also { store = it }

    suspend fun load(context: Context, track: DuplicateTrack, before: Pair<Long, Long?>,
        stepMs: Long): List<ComparisonFrame>? =
        loadCapture(context,track,before,stepMs,false)?.frames

    /** Only called inside withPair for a capture containing PCM. */
    suspend fun loadCapture(context:Context,track:DuplicateTrack,before:Pair<Long,Long?>,
        stepMs:Long,capturePcm:Boolean):ComparisonFrameCapture? {
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
            return ComparisonFrameCapture(it,null,false)
        }
        return codecs.withPermit {
            coroutineContext.ensureActive()
            if (!allowed()) return@withPermit null
            cached()?.let {
                if (!allowed() || MusicDuplicateScanner.comparisonStamp(context, track) != before) return@withPermit null
                AppLog.i("MusicCompare", "frames cache count=${it.size} stepMs=$stepMs elapsedMs=${SystemClock.elapsedRealtime()-started}")
                return@withPermit ComparisonFrameCapture(it,null,false)
            }
            val captured=AcousticAudioDecoder.comparisonCapture(context,Uri.parse(track.uri),stepMs,capturePcm)
                ?: return@withPermit null
            val frames=captured.frames
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
            captured
        }
    }

    /** A warm frame hit with no cached pair needs only its missing PCM stream.
     * Never retry a capture that exceeded the existing PCM duration limit. */
    suspend fun missingPcm(context:Context,track:DuplicateTrack,before:Pair<Long,Long?>,
        captured:ComparisonFrameCapture):AudioPcmBlocks? {
        fun allowed()=DuplicatePrivacyGuard.accepts(context,track.privacyEpoch,track.id)
        if(!allowed()||MusicDuplicateScanner.comparisonStamp(context,track)!=before)return null
        if(captured.pcmAttempted)return captured.pcm
        return codecs.withPermit {
            coroutineContext.ensureActive()
            if(!allowed()||MusicDuplicateScanner.comparisonStamp(context,track)!=before)return@withPermit null
            val pcm=AcousticAudioDecoder.comparisonPcm(context,Uri.parse(track.uri))
            coroutineContext.ensureActive()
            pcm?.takeIf{allowed()&&MusicDuplicateScanner.comparisonStamp(context,track)==before}
        }
    }

    /** Must run under DuplicatePrivacyGuard.lock together with epoch invalidation. */
    fun clearForPrivacy(context: Context) {
        val removed = store?.clear() ?: directory(context).let { !it.exists() || it.deleteRecursively() }
        store = null
        check(removed) { "Не удалось очистить кеш сравнения музыки" }
    }
}
