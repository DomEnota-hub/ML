package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.*
import com.domenota.medialoader.data.library.DuplicatePrivacyGuard
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class TrackRecognitionCacheTest{
    @Test fun cachedMatchRequiresSameBytesAndUriAndClearsWithPrivacy(){
        val context=RuntimeEnvironment.getApplication()
        val version=TrackRecognitionVersion("track","content://media/external/audio/media/42","a".repeat(64),DuplicatePrivacyGuard.generation(context))
        val result=MusicRecognitionResult("track-id","Title","Artist","Album","https://example.org/cover",RecognitionSource.SHAZAM_DIRECT)
        try{
            assertTrue(TrackRecognitionCache.save(context,version,result))
            assertEquals(result,TrackRecognitionCache.load(context,version))
            assertNull(TrackRecognitionCache.load(context,version.copy(sha256="b".repeat(64))))
            assertNull(TrackRecognitionCache.load(context,version.copy(uri="content://media/external/audio/media/43")))
            TrackRecognitionCache.clearForPrivacy(context)
            assertNull(TrackRecognitionCache.load(context,version))
        }finally{TrackRecognitionCache.clearForPrivacy(context)}
    }
    @Test fun rejectedJournalSurvivesReopenAndNeverLeaksToAnotherVersion(){
        val context=RuntimeEnvironment.getApplication()
        val version=TrackRecognitionVersion("track","content://media/external/audio/media/42","c".repeat(64),DuplicatePrivacyGuard.generation(context))
        val result=MusicRecognitionResult("old","Wrong","Artist",source=RecognitionSource.SHAZAM_DIRECT)
        val checked=CheckedAudioWindow(0,12000)
        val state=TrackRetryState(listOf(0L,24000L),listOf(checked),candidate=RetryCandidate(result,checked,TrackVerification.FIRST_MATCH))
        try{
            assertTrue(TrackRecognitionCache.saveRetry(context,version,state))
            assertEquals(state,TrackRecognitionCache.loadRetry(context,version))
            assertTrue(TrackRecognitionCache.saveRetry(context,version,state.reject(result)))
            val reopened=TrackRecognitionCache.loadRetry(context,version.copy(id="alias"))!!
            assertNull(reopened.candidate);assertEquals(listOf(result),reopened.rejected)
            assertEquals(listOf(24000L),reopened.remaining)
            assertNull(TrackRecognitionCache.loadRetry(context,version.copy(sha256="d".repeat(64))))
            assertNull(TrackRecognitionCache.loadRetry(context,version.copy(uri="content://media/external/audio/media/43")))
            TrackRecognitionCache.clearForPrivacy(context)
            assertNull(TrackRecognitionCache.loadRetry(context,version))
        }finally{TrackRecognitionCache.clearForPrivacy(context)}
    }
}
