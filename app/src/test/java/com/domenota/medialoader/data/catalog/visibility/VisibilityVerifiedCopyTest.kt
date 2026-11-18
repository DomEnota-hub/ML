package com.domenota.medialoader.data.catalog.visibility

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VisibilityVerifiedCopyTest {
    private fun fingerprint(data: ByteArray): VisibilityFingerprint =
        VisibilityFingerprint(data.size.toLong(),MessageDigest.getInstance("SHA-256")
            .digest(data).joinToString("") { "%02x".format(it.toInt() and 255) })

    @Test fun verifiedCopyMatchesBytesAndDurablyReplaysAcrossBothDirections() = runBlocking {
        val bytes=ByteArray(640_501) { (it * 31).toByte() }
        val target=File.createTempFile("visibility-shared-copy-", ".tmp")
        try {
            for (direction in VisibilityDirection.values()) {
                ByteArrayInputStream(bytes).use { source ->
                    FileOutputStream(target,false).use { dest ->
                        VisibilityVerifiedCopy.writeAndSync(source,dest,fingerprint(bytes))
                    }
                }
                assertArrayEquals(bytes,target.readBytes())
                assertEquals(fingerprint(bytes),HiddenFolderFileStore.fingerprint(target.inputStream()))
            }
        } finally { target.delete() }
    }

    @Test fun truncatedAndChangedInputsNeverGetPositiveCopyProof() = runBlocking {
        val original=ByteArray(25_000) { it.toByte() }
        val target=File.createTempFile("visibility-shared-copy-", ".tmp")
        try {
            for(input in listOf(original.copyOfRange(0,original.size-1),
                original.copyOf().also { it[100]=99 })) {
                val completed=runCatching {
                    ByteArrayInputStream(input).use { source ->
                        FileOutputStream(target,false).use { dest ->
                            VisibilityVerifiedCopy.writeAndSync(source,dest,fingerprint(original))
                        }
                    }
                }
                assertTrue(completed.isFailure)
            }
        } finally { target.delete() }
    }

    @Test fun unexpectedlyLargerSourceIsStoppedBeforeWritingTheExcess() = runBlocking {
        val original=byteArrayOf(1,2,3,4)
        val larger=byteArrayOf(1,2,3,4,5)
        val target=File.createTempFile("visibility-shared-copy-", ".tmp")
        try {
            assertTrue(runCatching {
                ByteArrayInputStream(larger).use { source ->
                    FileOutputStream(target,false).use { dest ->
                        VisibilityVerifiedCopy.writeAndSync(source,dest,fingerprint(original))
                    }
                }
            }.isFailure)
        } finally { target.delete() }
    }
}
