package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class VisibilityPendingJournalTest {
    @Test fun onlyMissingOrVerifiedEmptyJournalIsIdle() {
        val root = Files.createTempDirectory("visibility-pending").toFile()
        try {
            val f = File(root,"batch")
            fun pending() = VisibilityPendingJournal.pending(f) { VisibilityBatchCodec.decode(it) == null }
            assertFalse(pending())
            f.writeBytes(VisibilityBatchCodec.encode(null)); assertFalse(pending())
            f.writeBytes(VisibilityBatchCodec.encode(VisibilityBatchRecord("batch","f",VisibilityDirection.HIDE,emptyList())))
            assertTrue(pending())
            f.writeBytes(VisibilityBatchCodec.encode(null).also { it[7] = 99 }); assertTrue(pending())
            f.writeBytes(ByteArray(0)); assertTrue(pending())
            f.delete(); f.mkdir(); assertTrue(pending())
        } finally { root.deleteRecursively() }
    }
}
