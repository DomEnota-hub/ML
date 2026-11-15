package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalTrackRecognitionSearchTest{
    private val match=MusicRecognitionResult("id","Title","Artist",source=RecognitionSource.SHAZAM_DIRECT)
    @Test fun alternativesAreBoundedAndStopAtFirstValidMatch()=runBlocking{
        val decoded=mutableListOf<Long>()
        val stages=mutableListOf<TrackRecognitionProgress>()
        var calls=0
        val provider=object:MusicRecognitionProvider{
            override suspend fun recognize(pcm16Mono16k:ShortArray):MusicRecognitionResult?{
                calls++;return if(calls==2)match else null
            }
        }
        val starts=listOf(12000L,48000L,96000L,0L)
        val result=LocalTrackRecognitionSearch.find(starts,0,3,{at->decoded+=at;ShortArray(RecognitionAudioSpec.TARGET_SAMPLES){1000}},provider,{stages+=it})
        assertEquals(match,result.result);assertEquals(2,result.nextIndex)
        assertEquals(starts.take(2),decoded)
        assertEquals(listOf(TrackRecognitionStage.DECODING,TrackRecognitionStage.SEARCHING,TrackRecognitionStage.DECODING,TrackRecognitionStage.SEARCHING),stages.map{it.stage})
    }
    @Test fun shortSamplesAndBlankMatchesDoNotProduceAResult()=runBlocking{
        var calls=0
        val provider=object:MusicRecognitionProvider{
            override suspend fun recognize(pcm16Mono16k:ShortArray):MusicRecognitionResult?{
                calls++;return match.copy(title="")
            }
        }
        val result=LocalTrackRecognitionSearch.find(listOf(0L,12_000L,24_000L),0,2,
            {at->ShortArray(if(at==0L)20 else RecognitionAudioSpec.TARGET_SAMPLES){1000}},provider)
        assertNull(result.result);assertEquals(2,result.nextIndex);assertEquals(1,calls)
        assertNull(LocalTrackRecognitionSearch.find(listOf(0L),1,3,{error("No new windows")},provider).result)
    }
}
