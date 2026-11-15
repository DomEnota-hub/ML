package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.core.recognition.RecognitionSource
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectMp3TagsTest {
    @get:Rule val temp = TemporaryFolder()
    private val result = MusicRecognitionResult(title="Буква Ю",artist="Neverlove",album="Звуки",source=RecognitionSource.SHAZAM_DIRECT)
    // MPEG1 Layer III, 128 kbps, 44.1kHz, 417-byte frames.
    private fun audio() = ByteArray(417*3).apply { repeat(3) { n -> byteArrayOf(-1,-5,-112,0).copyInto(this,n*417) } }
    private fun length(n:Int, sync:Boolean):ByteArray { val shift=if(sync)7 else 8; return ByteArray(4){i->((n ushr ((3-i)*shift)) and ((1 shl shift)-1)).toByte()} }
    private fun frame(id:String,data:ByteArray,version:Int)=id.toByteArray()+length(data.size,version==4)+byteArrayOf(0,0)+data
    private fun tag(version:Int,vararg frames:ByteArray):ByteArray { val body=frames.fold(byteArrayOf()){a,b->a+b};return byteArrayOf(73,68,51,version.toByte(),0,0)+length(body.size,true)+body }
    private fun bodyEnd(bytes:ByteArray)=10+(6..9).fold(0){n,i->(n shl 7)+(bytes[i].toInt() and 127)}
    @Test fun newTagsPreserveEveryMpegByteAndUnrelatedFramesInV23AndV24() {
        for(version in 3..4) {
            val data=audio(); val comment=frame("TXXX",byteArrayOf(0)+"custom\u0000value".toByteArray(),version)
            val original=tag(version,frame("TIT2",byteArrayOf(0)+"old".toByteArray(),version),comment)+data
            val input=temp.newFile().apply{writeBytes(original)};val output=temp.newFile()
            DirectMp3Tags.write(input,output,result,null,true)
            val bytes=output.readBytes();assertArrayEquals(data,bytes.copyOfRange(bodyEnd(bytes),bytes.size))
            assertTrue(bytes.copyOfRange(0,bodyEnd(bytes)).toList().windowed(comment.size).any{it==comment.toList()})
            assertArrayEquals(original,input.readBytes()); assertEquals(version,bytes[3].toInt())
        }
    }
    @Test fun newArtworkReplacesOldWhileTagOnlyCanPreserveOrRemoveIt() {
        val old=frame("APIC",byteArrayOf(0,1,2,3,4),3)
        val input=temp.newFile().apply{writeBytes(tag(3,old)+audio())};val output=temp.newFile()
        DirectMp3Tags.write(input,output,result,null,true)
        assertTrue(String(output.readBytes(),Charsets.ISO_8859_1).contains("APIC"))
        DirectMp3Tags.write(input,output,result,null,false)
        assertFalse(String(output.readBytes(),Charsets.ISO_8859_1).contains("APIC"))
        val cover=temp.newFile().apply{writeBytes(byteArrayOf(-1,-40,-1,0,1,2))}
        DirectMp3Tags.write(input,output,result,cover,true)
        assertTrue(String(output.readBytes(),Charsets.ISO_8859_1).contains("image/jpeg"))
        assertArrayEquals(audio(),output.readBytes().let{it.copyOfRange(bodyEnd(it),it.size)})
    }
    @Test fun rejectsFalseMp3AndUnsupportedTagLayoutWithoutChangingInput() {
        val output=temp.newFile()
        for(bytes in listOf(ByteArray(2000),tag(2)+audio(),tag(3).apply{this[5]=128.toByte()}+audio())) {
            val input=temp.newFile().apply{writeBytes(bytes)}
            try { DirectMp3Tags.write(input,output,result,null,true);fail("Expected rejection") } catch (_: IllegalArgumentException) {}
            assertArrayEquals(bytes,input.readBytes())
        }
    }
    @Test fun legacyTagPreservesNonEditedFieldsAndDoesNotKeepWrongTitle() {
        val legacy=ByteArray(128).apply{"TAGold title".toByteArray().copyInto(this);this[127]=12}
        val input=temp.newFile().apply{writeBytes(audio()+legacy)};val output=temp.newFile()
        DirectMp3Tags.write(input,output,result,null,true)
        val bytes=output.readBytes();assertEquals(12,bytes.last().toInt());assertArrayEquals(audio(),bytes.copyOfRange(bodyEnd(bytes),bytes.size-128))
        assertFalse(String(bytes.copyOfRange(bytes.size-128,bytes.size),Charsets.ISO_8859_1).contains("old title"))
    }
}
