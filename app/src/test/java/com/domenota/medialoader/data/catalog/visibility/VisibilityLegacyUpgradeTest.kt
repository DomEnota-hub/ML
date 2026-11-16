package com.domenota.medialoader.data.catalog.visibility

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VisibilityLegacyUpgradeTest {
    private fun legacy(index: Int = 1) = VisibilityMoveRecord("operation-$index", "folder", "member-$index",
        VisibilityDirection.HIDE, "content://media/external/images/media/$index", "photo-$index.jpg", 123_000L,
        VisibilityFingerprint(12L, "a".repeat(64)), "token-0000000000$index")

    private fun enriched(old: VisibilityMoveRecord): VisibilityMoveRecord {
        val source = old.sourceUri.replace("/external/", "/external_primary/")
        return old.copy(sourceUri = source, catalogSourceUri = old.logicalSourceUri,
            sourceModifiedMillis = 123_000L, publicOrigin = VisibilityPublicOrigin(source, "external_primary", "Pictures/",
                old.sourceName, "image/jpeg", "/storage/emulated/0/Pictures/${old.sourceName}", 12L, 123L, 100L, null, null))
    }

    private class Journals(var batch: VisibilityBatchRecord?, var move: VisibilityMoveRecord? = null) {
        var batchWrites = 0
        var moveWrites = 0
        var interruptMove = false
        val batches = object : VisibilityBatchJournal {
            override suspend fun read() = batch
            override suspend fun replace(expected: VisibilityBatchRecord?, next: VisibilityBatchRecord?) {
                check(batch == expected); check(next != null); batch = next; batchWrites++
            }
        }
        val moves = object : VisibilityMoveJournal {
            override suspend fun read() = move
            override suspend fun replace(expected: VisibilityMoveRecord?, next: VisibilityMoveRecord?) {
                if (interruptMove) error("synthetic process interruption")
                check(move == expected); check(next != null); move = next; moveWrites++
            }
        }
    }

    @Test fun populatedVersionOneBatchUpgradesAll46WithoutChangingCursorOrIdentities() = runBlocking {
        val old = (1..46).map(::legacy)
        val decoded = requireNotNull(VisibilityBatchCodec.decode(versionOneBatch(old)))
        assertTrue(decoded.members.all { it.publicOrigin == null })
        val j = Journals(decoded)
        VisibilityLegacyUpgrade(j.batches, j.moves, ::enriched).run()
        val upgraded = requireNotNull(j.batch)
        assertEquals(0, upgraded.next)
        assertEquals(46, upgraded.members.size)
        assertEquals(decoded.id, upgraded.id)
        assertEquals(old.map { it.operationId }, upgraded.members.map { it.operationId })
        assertEquals(old.map { it.destinationToken }, upgraded.members.map { it.destinationToken })
        assertEquals(old.map { it.fingerprint }, upgraded.members.map { it.fingerprint })
        assertEquals(old.map { it.logicalSourceUri }, upgraded.members.map { it.logicalSourceUri })
        assertTrue(upgraded.members.all { it.publicOrigin != null })
        assertEquals(1, j.batchWrites); assertEquals(0, j.moveWrites)
    }

    @Test fun denialOnLastMemberLeavesBothJournalsUntouched() = runBlocking {
        val batch = VisibilityBatchRecord("batch", "folder", VisibilityDirection.HIDE, (1..46).map(::legacy))
        val j = Journals(batch, batch.members.first())
        fails {
            VisibilityLegacyUpgrade(j.batches, j.moves) {
                if (it.downloadId == "member-46") throw SecurityException("Synthetic permission revoked")
                enriched(it)
            }.run()
        }
        assertEquals(batch, j.batch); assertEquals(batch.members.first(), j.move)
        assertEquals(0, j.batchWrites); assertEquals(0, j.moveWrites)
    }

    @Test fun interruptionBetweenJournalWritesReusesExactDurableMemberOnRestart() = runBlocking {
        val old = legacy()
        val pending = old.copy(phase = VisibilityMovePhase.LINKED, destinationUri = "file:///synthetic/hidden/photo.jpg")
        val j = Journals(VisibilityBatchRecord("batch", "folder", VisibilityDirection.HIDE, listOf(old)), pending)
        j.interruptMove = true
        fails { VisibilityLegacyUpgrade(j.batches, j.moves, ::enriched).run() }
        assertNotNull(j.batch!!.members.single().publicOrigin)
        assertEquals(pending, j.move)
        j.interruptMove = false
        VisibilityLegacyUpgrade(j.batches, j.moves) { error("Must reuse durable exact metadata") }.run()
        assertEquals(VisibilityMovePhase.LINKED, j.move!!.phase)
        assertEquals(pending.destinationUri, j.move!!.destinationUri)
        assertEquals(j.batch!!.members.single(), j.move!!.copy(phase = VisibilityMovePhase.PREPARED, destinationUri = null))
        assertEquals(1, j.batchWrites); assertEquals(1, j.moveWrites)
    }

    @Test fun pendingOtherOperationOrOtherHashCannotBorrowUpgradedMetadata() = runBlocking {
        val member = enriched(legacy())
        for (foreign in listOf(legacy().copy(operationId = "other"), legacy().copy(
            fingerprint = VisibilityFingerprint(12L, "b".repeat(64))))) {
            val j = Journals(VisibilityBatchRecord("batch", "folder", VisibilityDirection.HIDE, listOf(member)), foreign)
            fails { VisibilityLegacyUpgrade(j.batches, j.moves) { error("Unexpected enrichment") }.run() }
            assertEquals(foreign, j.move); assertEquals(0, j.batchWrites); assertEquals(0, j.moveWrites)
        }
    }

    @Test fun showWithoutDurableOriginIsBlockedWithoutCallingSourceDiscovery() = runBlocking {
        val show = legacy().copy(direction = VisibilityDirection.SHOW)
        val batch = VisibilityBatchRecord("batch", "folder", VisibilityDirection.SHOW, listOf(show))
        val j = Journals(batch)
        fails { VisibilityLegacyUpgrade(j.batches, j.moves) { error("SHOW cannot guess placement") }.run() }
        assertEquals(batch, j.batch); assertEquals(0, j.batchWrites)
    }

    @Test fun enricherCannotChangeHashOrKnownDateOrLogicalIdentity() = runBlocking {
        val old = legacy()
        for (changed in listOf(enriched(old).copy(fingerprint = VisibilityFingerprint(12L, "b".repeat(64))),
            enriched(old).let { it.copy(sourceModifiedMillis = 124_000L, publicOrigin = it.publicOrigin!!.copy(modifiedSeconds = 124L)) },
            enriched(old).copy(catalogSourceUri = "content://media/external/images/media/999"))) {
            val batch = VisibilityBatchRecord("batch", "folder", VisibilityDirection.HIDE, listOf(old))
            val j = Journals(batch)
            fails { VisibilityLegacyUpgrade(j.batches, j.moves) { changed }.run() }
            assertEquals(batch, j.batch); assertEquals(0, j.batchWrites)
        }
    }

    @Test fun unknownLegacyDateCanAcquireVerifiedOriginDateAndModernRecordsAreUnchanged() = runBlocking {
        val old = legacy().copy(sourceModifiedMillis = null)
        val j = Journals(VisibilityBatchRecord("batch", "folder", VisibilityDirection.HIDE, listOf(old)))
        VisibilityLegacyUpgrade(j.batches, j.moves, ::enriched).run()
        assertEquals(123_000L, j.batch!!.members.single().sourceModifiedMillis)
        val modern = j.batch
        VisibilityLegacyUpgrade(j.batches, j.moves) { error("Modern record must not be rewritten") }.run()
        assertEquals(modern, j.batch); assertEquals(1, j.batchWrites)
    }

    private suspend fun fails(action: suspend () -> Unit) {
        try { action(); fail("Expected fail-closed recovery") }
        catch (_: IllegalStateException) { } catch (_: SecurityException) { }
    }

    private fun versionOneBatch(members: List<VisibilityMoveRecord>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x4d4c4642); out.writeInt(1); out.writeBoolean(true)
            out.writeUTF("legacy-batch"); out.writeUTF("folder"); out.writeUTF("HIDE")
            out.writeInt(0); out.writeInt(members.size)
            members.forEach { member ->
                // v1 predates both optional public-origin and logical-alias fields.
                val current = VisibilityJournalCodec.encode(member)
                val payload = current.copyOfRange(0, current.size - 34)
                java.nio.ByteBuffer.wrap(payload).putInt(4, 1)
                val encoded = payload + MessageDigest.getInstance("SHA-256").digest(payload)
                out.writeInt(encoded.size); out.write(encoded)
            }
        }
        val payload = bytes.toByteArray()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }
}
