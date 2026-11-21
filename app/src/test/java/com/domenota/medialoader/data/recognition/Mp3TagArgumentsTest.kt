package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.core.recognition.RecognitionSource
import org.junit.Assert.*
import org.junit.Test

class Mp3TagArgumentsTest {
    private val result=MusicRecognitionResult(title="Буква Ю \"test\"",artist="Neverlove",source=RecognitionSource.SHAZAM_DIRECT)
    @Test fun fallbackReallyMapsAudioOnlyAndDoesNotEncodeOrMapOldCover() {
        val args=Mp3TagArguments.build("in path.mp3","out.mp3",result,null,false)
        assertEquals(1,args.count{it=="-map"}); assertTrue("0:a:0" in args); assertTrue("-vn" in args)
        assertFalse("0:v?" in args); assertEquals("0",args[args.indexOf("-write_id3v1")+1]); assertTrue("title=${result.title}" in args); assertTrue("in path.mp3" in args)
    }
    @Test fun fallbackWithExistingArtworkMustCopyOldCoverInsteadOfDroppingIt() {
        val args=Mp3TagArguments.build("in.mp3","out.mp3",result,null,true)
        assertEquals(2,args.count{it=="-map"})
        assertTrue("0:v?" in args)
        assertFalse("-vn" in args)
        assertEquals("copy",args[args.indexOf("-c:v")+1])
    }
    @Test fun normalizedCoverIsCopiedInsteadOfRequiringVideoEncoder() {
        val args=Mp3TagArguments.build("in.mp3","out.mp3",result,"cover.jpg",true)
        assertEquals("copy",args[args.indexOf("-c:v")+1]);assertTrue("attached_pic" in args);assertFalse("mjpeg" in args)
    }
    @Test fun diagnosticsExposeOnlyAllowlistedCause() {
        assertEquals("encoder_unavailable",Mp3TagArguments.failureKind("Unknown encoder 'mjpeg' for /private/path"))
        assertEquals("unclassified_ffmpeg_error",Mp3TagArguments.failureKind("secret data"))
    }
}
