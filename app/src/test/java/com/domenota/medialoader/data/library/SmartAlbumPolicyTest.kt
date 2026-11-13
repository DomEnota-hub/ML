package com.domenota.medialoader.data.library
import org.junit.Assert.*
import org.junit.Test
class SmartAlbumPolicyTest {
    @Test fun unavailableNativeEngineIsRecoverableButCancellationIsNot() = kotlinx.coroutines.runBlocking {
        assertNull(SmartAlbumPolicy.optionalRecognition<Boolean> { throw UnsatisfiedLinkError("missing native OCR") })
        assertNull(SmartAlbumPolicy.optionalRecognition<Boolean> { throw NoClassDefFoundError("OCR initialization failed") })
        assertEquals(false,SmartAlbumPolicy.optionalRecognition { false })
        try {
            SmartAlbumPolicy.optionalRecognition<Boolean> { throw kotlinx.coroutines.CancellationException("cancel") }
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }
    @Test fun resolutionUsesPixelAreaNotSingleDimension() {
        assertTrue(SmartAlbumPolicy.highResolution(6000,4000,24))
        assertFalse(SmartAlbumPolicy.highResolution(8000,1000,24))
        assertFalse(SmartAlbumPolicy.highResolution(0,1000,8))
    }
    @Test fun documentRequiresMultiLineEvidence() {
        assertFalse(SmartAlbumPolicy.document("A meme caption",99,true))
        assertTrue(SmartAlbumPolicy.document("Railway operation register\nInspection time and date\nTechnical equipment list",85,true))
        assertFalse(SmartAlbumPolicy.document("Railway operation register\nInspection time and date\nTechnical equipment list",20,true))
    }
    @Test fun faceAndSelfieAreDifferentHeuristics() {
        assertTrue(SmartAlbumPolicy.portrait(.15f,1))
        assertFalse(SmartAlbumPolicy.selfie(false,.15f,1,true))
        assertTrue(SmartAlbumPolicy.selfie(true,.15f,1,true))
        assertFalse(SmartAlbumPolicy.selfie(true,.5f,0,true))
    }
}
