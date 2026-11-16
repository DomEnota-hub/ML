package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.core.recognition.RecognitionSource
import org.junit.Assert.*
import org.junit.Test

class AudioMetadataFormatTest {
    private fun identify(bytes:ByteArray)=AudioMetadataFormat.container(bytes.inputStream())
    private val mp3=ByteArray(834).apply{val h=byteArrayOf(-1,-5,-112,0);h.copyInto(this,0);h.copyInto(this,417)}
    @Test fun extensionAndId3AreNotEvidenceOfMp3(){
        assertEquals(AudioContainer.MP3,identify(mp3))
        val id3=byteArrayOf(73,68,51,3,0,0,0,0,0,3)+byteArrayOf(0,0,0)
        assertEquals(AudioContainer.MP3,identify(id3+mp3))
        assertEquals(AudioContainer.FLAC,identify(id3+"fLaC".toByteArray()))
        assertEquals(AudioContainer.UNKNOWN,identify(id3))
        assertEquals(AudioContainer.UNKNOWN,identify(mp3.copyOf(417)))
        assertEquals(AudioContainer.MP4,identify(byteArrayOf(0,0,0,24)+"ftypM4A ".toByteArray()))
        assertEquals(AudioContainer.AAC,identify(byteArrayOf(-1,-15,80,0,0,0,0)))
        assertEquals(AudioContainer.OGG,identify("OggS".toByteArray()))
        assertEquals(AudioContainer.WAV,identify("RIFF0000WAVE".toByteArray()))
    }
    @Test fun onlyActualCanonicalMp3MayReplaceOriginal(){
        fun format(container:AudioContainer,codec:String)=AudioMetadataFormat.classify(container,listOf(codec),10_000,false)
        assertEquals(AudioMetadataAction.REPLACE_MP3,format(AudioContainer.MP3,"audio/mpeg").action(true))
        assertEquals(AudioMetadataAction.TAGGED_COPY,format(AudioContainer.MP3,"audio/mpeg").action(false))
        assertEquals(AudioMetadataAction.TAGGED_COPY,format(AudioContainer.MP4,"audio/mp4a-latm").action(true))
        assertEquals(AudioTagFormat.FLAC,format(AudioContainer.FLAC,"audio/flac").tagFormat)
        assertEquals(AudioTagFormat.M4A,format(AudioContainer.MP4,"audio/alac").tagFormat)
        assertEquals(AudioMetadataAction.CONVERT_MP3,format(AudioContainer.MP4,"audio/mpeg").action(true))
        assertEquals(AudioMetadataAction.CONVERT_MP3,format(AudioContainer.OGG,"audio/opus").action(false))
        assertEquals(AudioMetadataAction.CONVERT_MP3,format(AudioContainer.AAC,"audio/mp4a-latm").action(false))
    }
    @Test fun videoMultipleAudioUnknownTracksAndMissingDurationFailClosed(){
        for(tracks in listOf(listOf("audio/mpeg","audio/mpeg"),listOf("audio/mpeg","video/avc"),listOf("audio/mpeg",null),emptyList()))
            assertEquals(AudioMetadataAction.UNAVAILABLE,AudioMetadataFormat.classify(AudioContainer.MP3,tracks,1000,false).action(true))
        assertEquals(AudioMetadataAction.UNAVAILABLE,AudioMetadataFormat.classify(AudioContainer.MP4,listOf("audio/mp4a-latm"),1000,true).action(true))
        assertEquals(AudioMetadataAction.UNAVAILABLE,AudioMetadataFormat.classify(AudioContainer.UNKNOWN,listOf("audio/opus"),null,false).action(false))
        assertEquals(AudioTagFormat.MP3,AudioMetadataFormat.classify(AudioContainer.MP3,listOf("audio/mpeg","image/jpeg"),1000,false).tagFormat)
    }
    @Test fun tagArgumentsNeverEncodeUnlessExplicitlyConverting(){
        val result=MusicRecognitionResult(title="Имя",artist="Исполнитель",album="Альбом",source=RecognitionSource.SHAZAM_DIRECT)
        for(format in AudioTagFormat.entries){
            val args=AudioMetadataArguments.build("source","output.${format.extension}",format,result,"cover.jpg",false)
            assertEquals("copy",args[args.indexOf("-c:a")+1]);assertFalse(args.contains("libmp3lame"))
            assertTrue(args.contains("attached_pic"));assertTrue(args.contains("title=Имя"));assertTrue(args.contains("-map_metadata"))
        }
        val convert=AudioMetadataArguments.build("source","out.mp3",AudioTagFormat.MP3,result,null,true)
        assertEquals("libmp3lame",convert[convert.indexOf("-c:a")+1]);assertTrue(convert.contains("192k"))
        assertTrue(AudioMetadataFormat.similarDuration(120000,120400))
        assertFalse(AudioMetadataFormat.similarDuration(120000,125000))
    }
}
