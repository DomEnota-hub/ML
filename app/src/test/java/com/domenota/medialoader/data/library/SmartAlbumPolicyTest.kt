package com.domenota.medialoader.data.library
import org.junit.Assert.*
import org.junit.Test
class SmartAlbumPolicyTest {
    private val photo=SmartPhotoFacts(6000,4000,0,0f,false,0,0,0,0f)
    @Test fun dimensionsUseRealPixelsAndInclusiveThreshold() {
        assertEquals(setOf(SmartAlbum.HIGH_RESOLUTION),SmartAlbumPolicy.categories(photo,24))
        assertTrue(SmartAlbumPolicy.categories(photo,25).isEmpty())
    }
    @Test fun faceAloneIsNotSelfie() {
        val portrait=photo.copy(faces=1,maxFaceArea=.15f)
        assertTrue(SmartAlbum.PORTRAITS in SmartAlbumPolicy.categories(portrait,24))
        assertFalse(SmartAlbum.SELFIES in SmartAlbumPolicy.categories(portrait,24))
        assertTrue(SmartAlbum.SELFIES in SmartAlbumPolicy.categories(portrait.copy(frontHint=true),24))
    }
    @Test fun textCaptionIsNotDocument() {
        assertFalse(SmartAlbum.DOCUMENTS in SmartAlbumPolicy.categories(photo.copy(words=3,lines=1,confidence=95,paleFraction=.9f),24))
        assertTrue(SmartAlbum.DOCUMENTS in SmartAlbumPolicy.categories(photo.copy(words=30,lines=8,confidence=80,paleFraction=.6f),24))
    }
}
