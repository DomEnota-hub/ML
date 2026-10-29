package com.domenota.medialoader.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
