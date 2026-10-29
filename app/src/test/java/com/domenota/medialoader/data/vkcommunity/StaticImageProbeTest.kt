package com.domenota.medialoader.data.vkcommunity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class StaticImageProbeTest {
    private fun check(bytes: ByteArray): StaticImageProbe.Format? {
        val file = File.createTempFile("vk-probe", ".bin")
        return try { file.writeBytes(bytes); StaticImageProbe.detect(file) } finally { file.delete() }
    }

    @Test fun jpegAndPngAreStatic() {
        assertEquals(StaticImageProbe.Format.JPEG,
            check(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())))
        assertEquals(StaticImageProbe.Format.PNG, check(byteArrayOf(
            137.toByte(), 80, 78, 71, 13, 10, 26, 10,
            0, 0, 0, 0, 73, 69, 78, 68, 0, 0, 0, 0,
        )))
    }

    @Test fun gifAndAnimatedPngAreRejectedRegardlessOfFilename() {
        assertNull(check("GIF89a".toByteArray()))
        val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10,
            0, 0, 0, 0, 97, 99, 84, 76, 0, 0, 0, 0)
        assertNull(check(png))
    }

    @Test fun animatedWebpRejectedAndStillWebpAccepted() {
        val prefix = "RIFF".toByteArray() + byteArrayOf(22, 0, 0, 0) + "WEBPVP8X".toByteArray() +
            byteArrayOf(10, 0, 0, 0)
        assertNull(check(prefix + byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals(StaticImageProbe.Format.WEBP,
            check(prefix + byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
    }
}
