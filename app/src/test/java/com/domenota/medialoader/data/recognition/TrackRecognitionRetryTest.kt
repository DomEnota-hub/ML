package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TrackRecognitionRetryTest {
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
}
