package com.domenota.medialoader.data.catalog.visibility

import android.provider.DocumentsContract
import org.junit.Assert.*
import org.junit.Test

class NomediaSafInventoryPolicyTest {
    @Test fun topLevelMediaAcceptedAndMarkerExcluded() {
        assertTrue(nomediaSafPhysicalFileAccepted("one.jpg", "image/jpeg", 0))
        assertTrue(nomediaSafPhysicalFileAccepted("two.mp3", "audio/mpeg", 0))
        assertTrue(nomediaSafPhysicalFileAccepted("clip.mp4", "video/mp4", 0))
        assertFalse(nomediaSafPhysicalFileAccepted(".nomedia", "application/octet-stream", 0))
    }

    @Test fun nestedDirectoryAndNestedMediaNeverPassInventory() {
        assertThrows(IllegalStateException::class.java) {
            nomediaSafPhysicalFileAccepted("subfolder", DocumentsContract.Document.MIME_TYPE_DIR, 0)
        }
        assertThrows(IllegalStateException::class.java) {
            nomediaSafPhysicalFileAccepted("other.jpg", "image/jpeg", 1)
        }
        assertThrows(IllegalStateException::class.java) {
            nomediaSafPhysicalFileAccepted(".nomedia", "application/octet-stream", 1)
        }
    }

    @Test fun unclassifiedFileAndSpoofedDirectoryMarkerBlockWrite() {
        assertThrows(IllegalStateException::class.java) {
            nomediaSafPhysicalFileAccepted("unknown.bin", "application/octet-stream", 0)
        }
        assertThrows(IllegalStateException::class.java) {
            nomediaSafPhysicalFileAccepted("notes.txt", "text/plain", 0)
        }
        assertThrows(IllegalStateException::class.java) {
            nomediaSafPhysicalFileAccepted(".nomedia", DocumentsContract.Document.MIME_TYPE_DIR, 0)
        }
    }
}
