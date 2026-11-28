package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FolderVisibilityBatchTest {
    private fun plan() = VisibilityBatchRecord("batch", "folder", VisibilityDirection.HIDE, (1..3).map {
        VisibilityMoveRecord("op$it", "folder", "id$it", VisibilityDirection.HIDE, "source$it", "same.jpg", 123L,
            VisibilityFingerprint(10L, "a".repeat(64)), "token$it")
    })
    @Test fun corruptBatchIsNotTreatedAsAnEmptyPlan() = runBlocking {
        val root = Files.createTempDirectory("ml-legacy-journal").toFile()
        try {
            val file = File(root,"batch")
            val journal = FileVisibilityBatchJournal(file)
            val batch = plan()
            journal.replace(null,batch)
            assertEquals(batch,FileVisibilityBatchJournal(file).read())
            val raw=file.readBytes()
            raw[10]=(raw[10]+1).toByte()
            file.writeBytes(raw)
            assertTrue(runCatching { FileVisibilityBatchJournal(file).read() }.isFailure)
            assertTrue(raw.contentEquals(file.readBytes()))
        } finally { root.deleteRecursively() }
    }

    @Test fun membershipCodecPreservesExternalSeedAndPriorReceipt() {
        val member=plan().members.first()
        val receipt=member.copy(phase=VisibilityMovePhase.CLEANED,destinationUri="file:///hidden.jpg")
        val change=VisibilityMemberChange(member.downloadId,false,member.folderId,"second",requireNotNull(receipt.destinationUri),true,"{\"name\":\"файл\"}",receipt)
        val batch=plan().copy(members=emptyList(),membership=change)
        assertEquals(batch,VisibilityBatchCodec.decode(VisibilityBatchCodec.encode(batch)))
    }

    @Test fun versionOneBatchRemainsReadable() {
        val batch=plan()
        val current=VisibilityBatchCodec.encode(batch)
        val payload=current.copyOfRange(0,current.size-33)
        payload[7]=1
        val old=payload+java.security.MessageDigest.getInstance("SHA-256").digest(payload)
        assertEquals(batch,VisibilityBatchCodec.decode(old))
    }
}
