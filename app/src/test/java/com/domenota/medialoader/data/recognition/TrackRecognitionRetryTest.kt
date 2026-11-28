package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Test

class TrackRecognitionRetryTest {
    @Test fun cancellationReturnsUnsubmittedWindowButKeepsAnAlreadySubmittedOne()=runBlocking {
        val initial=TrackRetryState(listOf(0L,24000L),rejected=listOf(wrong))
        for(sent in listOf(false,true)) {
            var saved=initial;val waiting=CompletableDeferred<Unit>()
            val provider=object:SubmissionAwareMusicRecognitionProvider {
                override suspend fun recognize(pcm16Mono16k:ShortArray):MusicRecognitionResult? {
                    if(sent)markRecognitionSubmission()
                    waiting.complete(Unit);awaitCancellation()
                }
            }
            val job=launch{TrackRecognitionRetry.search(initial,3,{pcm()},provider,{saved=it})}
            waiting.await();job.cancelAndJoin()
            assertEquals(if(sent)listOf(24000L)else initial.starts,saved.remaining)
            assertEquals(initial.rejected,saved.rejected)
        }
    }
    @Test fun laterLoopCooldownCannotUndoTheFirstSubmittedRequest()=runBlocking {
        var requests=0
        val api=ShazamApi {
            requests++
            if(requests==1){markRecognitionSubmission();null}
            else throw ShazamRateLimitException(60000,requestSent=false)
        }
        val provider=ShazamRecognitionProvider(api){ShazamSignatureGenerator.Signature("fixture",4000)}
        val initial=TrackRetryState(listOf(0L,24000L),rejected=listOf(wrong));var saved=initial
        try{TrackRecognitionRetry.search(initial,3,{ShortArray(4*16000){1000}},provider,{saved=it});fail("Expected local cooldown")}
        catch(_:ShazamRateLimitException){}
        assertEquals(2,requests);assertEquals(listOf(24000L),saved.remaining)
    }
    @Test fun localCooldownDoesNotConsumeUnsentWindowButActual429Does()=runBlocking {
        val initial=TrackRetryState(listOf(0L,24000L),rejected=listOf(wrong))
        for(sent in listOf(false,true)) {
            var saved=initial
            val limited=object:MusicRecognitionProvider {
                override suspend fun recognize(pcm16Mono16k:ShortArray):MusicRecognitionResult? {throw ShazamRateLimitException(60000,requestSent=sent)}
            }
            try{TrackRecognitionRetry.search(initial,3,{pcm()},limited,{saved=it});fail("Expected cooldown")}
            catch(e:ShazamRateLimitException){assertEquals(sent,e.requestSent)}
            assertEquals(if(sent)listOf(24000L)else listOf(0L,24000L),saved.remaining)
            assertEquals(listOf(wrong),saved.rejected);assertNull(saved.candidate)
        }
    }
    private val wrong=MusicRecognitionResult("wrong","Wrong","Artist",source=RecognitionSource.SHAZAM_DIRECT)
    private val right=MusicRecognitionResult("right","Right","Artist",source=RecognitionSource.SHAZAM_DIRECT)
    private val third=right.copy(trackId="third",title="Third")
    private fun provider(vararg replies: MusicRecognitionResult?): MusicRecognitionProvider = object:MusicRecognitionProvider {
        var calls=0
        override suspend fun recognize(pcm16Mono16k:ShortArray)=replies[calls++]
    }
    private fun pcm()=ShortArray(RecognitionAudioSpec.TARGET_SAMPLES){1000}
    @Test fun firstMatchDoesNotDoubleRequestAndRetrySkipsRejectedAndConfirmsIndependently()=runBlocking {
        val plan=TrackRetryState(listOf(0L,4000L,24000L,48000L,72000L))
        val decoded=mutableListOf<Long>();var disk=plan
        val first=TrackRecognitionRetry.search(plan,3,{decoded+=it;pcm()},provider(wrong),{disk=it})
        assertEquals(wrong,first.candidate?.result)
        assertEquals(listOf(0L),decoded)
        assertEquals(TrackVerification.FIRST_MATCH,first.candidate?.verification)
        val reopened=TrackRecognitionRetry.search(disk,3,{error("cache must not decode")},provider(),{})
        assertEquals(first,reopened)
        disk=first.reject(wrong)
        val next=TrackRecognitionRetry.search(disk,5,{decoded+=it;pcm()},provider(wrong,right,right),{disk=it})
        assertEquals(listOf(0L,24000L,48000L,72000L),decoded)
        assertEquals(TrackVerification.CONFIRMED,next.candidate?.verification)
        assertEquals(right,next.candidate?.result)
        assertEquals(4,next.checked.size)
        assertEquals(next,disk)
        assertTrue(next.checked.all{it.durationMs==12000L})
    }
    @Test fun rejectedResultDuringConfirmationDoesNotStopIndependentSearch()=runBlocking {
        val initial=TrackRetryState(listOf(0L,24000L,48000L,72000L),listOf(CheckedAudioWindow(0,12000)),listOf(wrong))
        val decoded=mutableListOf<Long>()
        val result=TrackRecognitionRetry.search(initial,5,{decoded+=it;pcm()},provider(right,wrong,right),{})
        assertEquals(listOf(24000L,48000L,72000L),decoded)
        assertEquals(right,result.candidate?.result)
        assertEquals(TrackVerification.CONFIRMED,result.candidate?.verification)
        assertTrue(result.conflict)
    }
    @Test fun conflictingIndependentResultIsNeverOfferedForWriting()=runBlocking {
        val initial=TrackRetryState(listOf(0L,24000L,48000L),listOf(CheckedAudioWindow(0,12000)),listOf(wrong))
        val result=TrackRecognitionRetry.search(initial,3,{pcm()},provider(right,third),{})
        assertTrue(result.conflict);assertNull(result.candidate);assertTrue(result.remaining.isEmpty())
    }
    @Test fun noIndependentWindowIsExplicitlyUnconfirmedAndShortFileDoesNotRetrySameWindow()=runBlocking {
        val initial=TrackRetryState(listOf(0L,5000L),listOf(CheckedAudioWindow(0,12000)),listOf(wrong))
        val result=TrackRecognitionRetry.search(initial,3,{pcm()},provider(right),{})
        assertEquals(TrackVerification.UNCONFIRMED,result.candidate?.verification)
        val exhausted=TrackRecognitionRetry.search(result.reject(right),3,{error("repeated window")},provider(),{})
        assertNull(exhausted.candidate);assertTrue(exhausted.remaining.isEmpty())
    }
    @Test fun providerFailurePreservesActualCheckedWindowAndRejectedResult()=runBlocking {
        val initial=TrackRetryState(listOf(0L,24000L,48000L),listOf(CheckedAudioWindow(0,12000)),listOf(wrong))
        var persisted=initial
        val failure=object:MusicRecognitionProvider { override suspend fun recognize(pcm16Mono16k:ShortArray):MusicRecognitionResult?=throw java.io.IOException("offline") }
        try{TrackRecognitionRetry.search(initial,3,{pcm()},failure,{persisted=it});fail("expected error")}
        catch(_:java.io.IOException){}
        assertEquals(listOf(48000L),persisted.remaining)
        assertEquals(listOf(wrong),persisted.rejected)
        assertNull(persisted.candidate)
    }
    @Test fun unsuccessfulWindowsAreBoundedAndFirstValidMatchStopsSearch()=runBlocking {
        val starts=listOf(0L,24000L,48000L,72000L)
        val decoded=mutableListOf<Long>()
        val progress=mutableListOf<TrackRecognitionProgress>()
        val result=TrackRecognitionRetry.search(TrackRetryState(starts),3,
            { decoded+=it;pcm() },provider(null,right),{},{progress+=it})
        assertEquals(right,result.candidate?.result)
        assertEquals(starts.take(2),decoded)
        assertEquals(starts.take(2),result.checked.map{it.startMs})
        assertEquals(listOf(TrackRecognitionStage.DECODING,TrackRecognitionStage.SEARCHING,
            TrackRecognitionStage.DECODING,TrackRecognitionStage.SEARCHING),progress.map{it.stage})
        val exhaustedBudget=TrackRecognitionRetry.search(TrackRetryState(starts),2,{pcm()},provider(null,null),{})
        assertNull(exhaustedBudget.candidate)
        assertEquals(starts.drop(2),exhaustedBudget.remaining)
    }

    @Test fun shortSamplesAndBlankMatchesConsumeBudgetWithoutOfferingCandidate()=runBlocking {
        val starts=listOf(0L,24000L,48000L,72000L)
        val decoded=mutableListOf<Long>()
        val result=TrackRecognitionRetry.search(TrackRetryState(starts),3,
            { decoded+=it; if(it==0L) ShortArray(20){1000} else pcm() },
            provider(right.copy(title=""),right.copy(artist="")),{})
        assertNull(result.candidate)
        assertEquals(starts.take(3),decoded)
        assertEquals(starts.drop(3),result.remaining)
        val done=TrackRetryState(listOf(0L),listOf(CheckedAudioWindow(0L,12000L)))
        assertNull(TrackRecognitionRetry.search(done,3,{error("No new windows")},provider(),{}).candidate)
    }
}
