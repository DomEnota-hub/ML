package com.domenota.medialoader.data.storage

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class WebpHeaderDatesTest {
    private val date = 1700000000123L
    private fun chunk(type: String, bytes: ByteArray): ByteArray = type.toByteArray() +
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array() + bytes +
        if (bytes.size and 1 == 1) byteArrayOf(0) else byteArrayOf()
    private fun riff(body: ByteArray) = "RIFF".toByteArray() + ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN).putInt(body.size + 4).array() + "WEBP".toByteArray() + body
    private fun vp8() = chunk("VP8 ", byteArrayOf(0,0,0,0x9d.toByte(),1,0x2a,4,0,5,0))
    private fun files(bytes: ByteArray, test: (File, File) -> Unit) {
        val dir = Files.createTempDirectory("webp-dates-qa").toFile()
        val source = File(dir,"wrong-extension.jpg").apply { writeBytes(bytes) }
        try { test(source, File(dir,"dated.jpg")); assertArrayEquals(bytes, source.readBytes()) }
        finally { dir.deleteRecursively() }
    }
    @Test fun extendedWebpPreservesColorProfileAndEncodedImage() = files(riff(
        chunk("VP8X",byteArrayOf(32,0,0,0,3,0,0,4,0,0)) + chunk("ICCP","qa-profile".toByteArray()) + vp8())) { source,out ->
        assertTrue(WebpHeaderDates.matches(source))
        WebpHeaderDates.copy(source,out,date); WebpHeaderDates.verify(source,out,date)
        assertTrue(out.length() > source.length())
        assertEquals(40, out.readBytes()[20].toInt())
    }
    @Test fun basicLossyAndLosslessWebpGetExtendedHeaderWithoutReencoding() {
        val lossless = chunk("VP8L", byteArrayOf(0x2f) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(3 or (4 shl 14) or (1 shl 28)).array())
        for (frame in listOf(vp8(),lossless)) files(riff(frame)) { source,out ->
            WebpHeaderDates.copy(source,out,date); WebpHeaderDates.verify(source,out,date)
            assertEquals("VP8X",String(out.readBytes(),12,4))
        }
    }
    @Test fun changedColorProfileFailsVerification() = files(riff(
        chunk("VP8X",byteArrayOf(32,0,0,0,3,0,0,4,0,0)) + chunk("ICCP","qa-profile".toByteArray()) + vp8())) { source,out ->
        WebpHeaderDates.copy(source,out,date)
        val bytes = out.readBytes()
        val at = String(bytes,Charsets.ISO_8859_1).indexOf("qa-profile")
        bytes[at] = (bytes[at].toInt() xor 1).toByte(); out.writeBytes(bytes)
        try { WebpHeaderDates.verify(source,out,date); fail("Changed ICC accepted") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("ICC")) }
    }
    @Test fun malformedAnimatedOrExistingExifFailsBeforeWriting() {
        val valid=riff(vp8())
        val badSize=valid.copyOf().apply { this[4]=0 }
        for (bytes in listOf(badSize, valid.copyOf(valid.size-1), riff(vp8()+chunk("EXIF", byteArrayOf(0))),
            riff(vp8()+chunk("ANMF",byteArrayOf(0))))) files(bytes) { source,out ->
            try { WebpHeaderDates.copy(source,out,date); fail("Unsafe WebP accepted") }
            catch (_: IllegalStateException) { assertFalse(out.exists()) }
        }
    }
}
