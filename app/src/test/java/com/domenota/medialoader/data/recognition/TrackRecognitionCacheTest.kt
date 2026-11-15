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
}
