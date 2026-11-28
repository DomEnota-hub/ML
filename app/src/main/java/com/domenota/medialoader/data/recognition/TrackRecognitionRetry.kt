package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal enum class TrackVerification { FIRST_MATCH, CONFIRMED, UNCONFIRMED }
internal data class CheckedAudioWindow(val startMs: Long, val durationMs: Long)
internal data class RetryCandidate(val result: MusicRecognitionResult, val window: CheckedAudioWindow,
    val verification: TrackVerification)
internal data class TrackRetryState(val starts: List<Long>, val checked: List<CheckedAudioWindow> = emptyList(),
    val rejected: List<MusicRecognitionResult> = emptyList(), val candidate: RetryCandidate? = null,
    val conflict: Boolean = false) {
    val remaining get() = starts.filter { start -> checked.none { it.startMs == start } }
    fun reject(result: MusicRecognitionResult): TrackRetryState = copy(
        rejected = (rejected + result).distinctBy { it.trackId ?: normalizedPair(it) },
        candidate = null, conflict = false)
}
private fun normalizedPair(result: MusicRecognitionResult) =
    result.artist.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ") + "\u0000" +
        result.title.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")
internal fun sameRecognition(a: MusicRecognitionResult, b: MusicRecognitionResult): Boolean {
    val ai = a.trackId?.takeIf(String::isNotBlank); val bi = b.trackId?.takeIf(String::isNotBlank)
    return if (ai != null && bi != null) ai == bi else normalizedPair(a) == normalizedPair(b)
}
internal fun rejectedRecognition(a: MusicRecognitionResult, b: MusicRecognitionResult): Boolean =
    sameRecognition(a,b) || normalizedPair(a) == normalizedPair(b)

/** Persist the requested offset and actual decoded PCM length before submitting the fingerprint.
 * No replay of a checked window; cancellation/network error cannot resurrect a rejected candidate. */
internal object TrackRecognitionRetry {
    private const val WINDOW = RecognitionAudioSpec.TARGET_SECONDS * 1000L
    private fun independent(start: Long, window: CheckedAudioWindow) =
        start >= window.startMs + window.durationMs || start + WINDOW <= window.startMs
    suspend fun search(initial: TrackRetryState, maxAttempts: Int,
        decode: suspend (Long) -> ShortArray, provider: MusicRecognitionProvider,
        persist: suspend (TrackRetryState) -> Unit,
        progress: suspend (TrackRecognitionProgress) -> Unit = {}): TrackRetryState {
        require(maxAttempts > 0)
        var state = initial
        if (state.candidate != null) return state
        var budget = maxAttempts
        val retry = state.rejected.isNotEmpty()
        val aware=provider is SubmissionAwareMusicRecognitionProvider
        var proposed: RetryCandidate? = null
        while (budget > 0 && state.remaining.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val pending = proposed
            val start = if (pending != null) {
                state.remaining.firstOrNull { s -> independent(s, pending.window) && state.checked.all { independent(s, it) } }
                    ?: state.remaining.firstOrNull { independent(it, pending.window) } ?: break
            } else {
                state.remaining.firstOrNull { s -> state.checked.all { independent(s, it) } } ?: state.remaining.first()
            }
            val index = state.starts.indexOf(start)
            progress(TrackRecognitionProgress(index,state.starts.size,TrackRecognitionStage.DECODING,start,null))
            val pcm = decode(start)
            val window = CheckedAudioWindow(start, RecognitionAudio.durationMs(pcm))
            val next=state.copy(checked=state.checked+window)
            var reserved=false
            suspend fun reserve(){state=next;reserved=true;persist(state)}
            if(!aware || !RecognitionAudio.eligible(pcm))reserve()
            budget--
            if (!RecognitionAudio.eligible(pcm)) continue
            progress(TrackRecognitionProgress(index,state.starts.size,TrackRecognitionStage.SEARCHING,start,window.durationMs))
            // Do not durably spend a fragment while waiting for the shared network gate.
            val submission=RecognitionSubmission{withContext(NonCancellable){reserve()}}
            val result = try{
                if(aware)withContext(submission){provider.recognize(pcm)} else provider.recognize(pcm)
            }catch(failure:Exception){
                val unsent=if(aware)!submission.sent else failure is ShazamRateLimitException&&!failure.requestSent
                if(unsent && reserved) {
                    // Cancellation during mutex/spacing waits must not spend a fragment.
                    // A later loop wait cannot undo an earlier actual send in this scope.
                    try{withContext(NonCancellable){state=state.copy(checked=state.checked.dropLast(1));persist(state)}}
                    catch(saveFailure:Exception){failure.addSuppressed(saveFailure)}
                }
                throw failure
            }.also{if(aware&&!reserved)reserve()}?.takeIf { it.title.isNotBlank() && it.artist.isNotBlank() } ?: continue
            if (state.rejected.any { rejectedRecognition(it,result) }) {
                // A repeat of the rejected song is not an alternative or a confirmation.
                if(pending!=null){
                    state=state.copy(conflict=true,candidate=null)
                    persist(state)
                }
                continue
            }
            if (pending != null) {
                if (sameRecognition(pending.result,result)) {
                    state = state.copy(candidate = pending.copy(verification=TrackVerification.CONFIRMED))
                    persist(state); return state
                }
                // Conflicting independent fingerprints: do not offer either for writing.
                state = state.copy(conflict=true,candidate=null)
                persist(state)
                return state
            }
            proposed = RetryCandidate(result,window,if(retry) TrackVerification.UNCONFIRMED else TrackVerification.FIRST_MATCH)
            if (!retry) break
        }
        state = state.copy(candidate=proposed)
        persist(state)
        return state
    }
}
