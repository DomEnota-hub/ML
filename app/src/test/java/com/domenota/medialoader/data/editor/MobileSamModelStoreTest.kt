package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.runBlocking

class MobileSamModelStoreTest {
    @Test fun downloadsExactlyTwoPinnedOnnxParts() {
        assertEquals(28_157_093L, MobileSamModelStore.ENCODER_BYTES)
        assertEquals(16_501_323L, MobileSamModelStore.DECODER_BYTES)
        assertEquals(44_658_416L, MobileSamModelStore.TOTAL_BYTES)
        assertEquals(64, MobileSamModelStore.ENCODER_SHA256.length)
        assertEquals(64, MobileSamModelStore.DECODER_SHA256.length)
        assertTrue(MobileSamModelStore.REVISION.length == 40)
        assertTrue(MobileSamModelStore.MIN_DISK_BYTES > MobileSamModelStore.TOTAL_BYTES)
    }

    @Test fun creatingStoreNeverDownloadsModelsAndRequiresBothForInstalledState() {
        val store = MobileSamModelStore(RuntimeEnvironment.getApplication())
        assertFalse(store.installed())
        assertFalse(runBlocking { store.verifyInstalled() })
    }

    @Test fun modelSourcePinnedToImmutableRevisionNotMainBranch() {
        val src = MobileSamModelStore.REVISION
        assertFalse(src.contains("main"))
        assertEquals("0d3b403339b4674a82493d5e97964dd78089ddc8", src)
    }
}
