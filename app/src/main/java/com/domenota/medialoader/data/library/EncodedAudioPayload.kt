package com.domenota.medialoader.data.library

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Exact ordered encoded samples, appended to the existing MIME/format/CSD digest.
 * Android's extractor JNI can copy a heap buffer's entire backing array for every
 * sample through Get/ReleaseByteArrayElements. A bounded direct buffer uses the
 * native address path instead. This changes transport, never the fingerprint. */
internal object EncodedAudioPayload {
    fun digestSamples(
        digest: MessageDigest,
        maxInputSize: Int?,
        checkActive: () -> Unit,
        read: (ByteBuffer) -> Int,
        advance: () -> Boolean,
    ): String? {
        val size = maxInputSize?.coerceIn(64 * 1024, 4 * 1024 * 1024) ?: 1024 * 1024
        val buffer = ByteBuffer.allocateDirect(size)
        var count = 0
        while (true) {
            checkActive()
            buffer.clear()
            val sizeRead = read(buffer)
            if (sizeRead < 0) break
            if (sizeRead > buffer.capacity()) return null
            buffer.position(0)
            buffer.limit(sizeRead)
            digest.update(buffer)
            count++
            if (!advance()) break
        }
        return if (count > 0) digest.digest().joinToString("") { "%02x".format(it) } else null
    }
}
