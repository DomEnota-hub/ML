package com.domenota.medialoader.data.recognition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShazamSignatureGeneratorTest {
    @Test fun silentOneSecondClipProducesValidShazamPacket() {
        val signature = ShazamSignatureGenerator.generate(ShortArray(16_000))

        assertEquals(1_000L, signature.sampleMs)
        assertTrue(signature.uri.startsWith("data:audio/vnd.shazam.sig;base64,"))

        val encoded = Base64.getDecoder().decode(signature.uri.substringAfter("base64,"))
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(0xcafe2580L, buffer.getInt(0).toLong() and 0xffffffffL)
        assertEquals(0x94119c00L, buffer.getInt(12).toLong() and 0xffffffffL)
        assertEquals(3 shl 27, buffer.getInt(28))
        assertEquals(16_000 + 3_840, buffer.getInt(40))
        assertEquals(56, encoded.size)

        val storedCrc = buffer.getInt(4).toLong() and 0xffffffffL
        val calculatedCrc = CRC32().apply { update(encoded, 8, encoded.size - 8) }.value
        assertEquals(calculatedCrc, storedCrc)
    }
}
