package com.domenota.medialoader.data.catalog.visibility

import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One bounded byte-copy primitive for both directions of ordinary hidden
 * folders, following the PrivateFileStore principle: durable copy first,
 * prove the exact bytes, then separately commit the owned destination.
 *
 * Never deletes sources or commits catalog state. Callers must additionally
 * rehash the published destination and enforce owner/receipt/journal proofs.
 */
internal object VisibilityVerifiedCopy {
    suspend fun writeAndSync(
        source: InputStream,
        output: FileOutputStream,
        expected: VisibilityFingerprint,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = source.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            check(n.toLong() <= expected.bytes - size) { "Исходный файл превышает подтверждённый размер" }
            output.write(buffer, 0, n)
            digest.update(buffer, 0, n)
            size += n
        }
        output.flush()
        output.fd.sync()
        val actual = VisibilityFingerprint(size,digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 255)
        })
        check(actual == expected) { "Содержимое копии не совпадает с исходным отпечатком" }
    }
}
