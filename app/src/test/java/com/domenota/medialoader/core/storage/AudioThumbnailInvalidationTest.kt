package com.domenota.medialoader.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AudioThumbnailInvalidationTest {
    @Test fun replacingAudioAtTheSameUriRemovesItsOldThumbnailAndChangesRevision() {
        val directory = Files.createTempDirectory("audio-thumb-test").toFile()
        try {
            val uri = "content://downloads/123"
            val cached = AudioThumbnailInvalidation.file(directory, uri)
            cached.parentFile!!.mkdirs()
            cached.writeBytes(byteArrayOf(1, 2, 3))
            val before = AudioThumbnailInvalidation.revision(uri)

            AudioThumbnailInvalidation.invalidate(directory, uri)

            assertFalse(cached.exists())
            assertEquals(before + 1, AudioThumbnailInvalidation.revision(uri))
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test fun rewritingAudioRemovesItsIdentityBoundCollectionCoversAndRejectsStalePublisher() {
        val directory = Files.createTempDirectory("audio-cover-test").toFile()
        try {
            val uri="content://downloads/cover-a"; val other="content://downloads/cover-b"
            val a=AudioThumbnailInvalidation.coverFile(directory,uri,"identity-a")
            val b=AudioThumbnailInvalidation.coverFile(directory,uri,"identity-b")
            val unrelated=AudioThumbnailInvalidation.coverFile(directory,other,"identity-a")
            listOf(a,b,unrelated).forEach{it.parentFile!!.mkdirs();it.writeBytes(byteArrayOf(1))}
            val old=AudioThumbnailInvalidation.revision(uri)
            AudioThumbnailInvalidation.invalidate(directory,uri)
            assertFalse(a.exists());assertFalse(b.exists());assertTrue(unrelated.exists())
            assertFalse(AudioThumbnailInvalidation.publishIfCurrent(uri,old){a.writeBytes(byteArrayOf(2))})
            assertFalse(a.exists())
            assertTrue(AudioThumbnailInvalidation.publishIfCurrent(uri,old+1){a.writeBytes(byteArrayOf(3))})
            assertEquals(3,a.readBytes().single().toInt())
        } finally { directory.deleteRecursively() }
    }
}
