package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.MusicRecognitionProvider
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Opt in only when every actual network send is marked at its throttled boundary.
 * One scope spans direct and looped fingerprints for the same audio fragment. */
internal interface SubmissionAwareMusicRecognitionProvider:MusicRecognitionProvider
internal class RecognitionSubmission(private val beforeFirstSend:suspend ()->Unit={}):AbstractCoroutineContextElement(Key) {
    companion object Key:CoroutineContext.Key<RecognitionSubmission>
    private val submitted=AtomicBoolean(false)
    private val gate=Mutex()
    val sent get()=submitted.get()
    suspend fun mark()=gate.withLock { if(!submitted.get()){beforeFirstSend();submitted.set(true)} }
}
internal suspend fun markRecognitionSubmission() {
    val context=currentCoroutineContext();context.ensureActive()
    context[RecognitionSubmission]?.mark()
}
