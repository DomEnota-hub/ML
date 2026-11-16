package com.domenota.medialoader.data.catalog.visibility

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A durable exact assignment change. External seeds contain the existing
 * catalog's bounded metadata format, never a guessed download record. */
internal data class VisibilityMemberChange(
    val id: String, val app: Boolean, val sourceFolder: String?, val targetFolder: String?,
    val sourceUri: String, val favorite: Boolean, val externalSeed: String?,
    val expectedReceipt: VisibilityMoveRecord? = null,
) {
    init {
        require(id.isNotBlank() && sourceUri.isNotBlank() && sourceFolder != targetFolder)
        require(sourceFolder == null || sourceFolder.isNotBlank())
        require(targetFolder == null || targetFolder.isNotBlank())
        require(app == (externalSeed == null))
        require(externalSeed == null || externalSeed.toByteArray(Charsets.UTF_8).size in 1..64*1024)
        require(expectedReceipt == null || expectedReceipt.downloadId == id)
    }
}

/** Immutable list of exact, preflighted members. No member is guessed from the current list on resume. */
internal data class VisibilityBatchRecord(
    val id: String,
    val folderId: String,
    val direction: VisibilityDirection,
    val members: List<VisibilityMoveRecord>,
    val next: Int = 0,
    val membership: VisibilityMemberChange? = null,
) {
    init {
        require(id.isNotBlank() && folderId.isNotBlank() && members.size <= MAX_MEMBERS && next in 0..members.size)
        require(members.all { it.folderId == folderId && it.direction == direction && it.phase == VisibilityMovePhase.PREPARED })
        require(members.map { it.operationId }.toSet().size == members.size)
        require(members.map { it.downloadId }.toSet().size == members.size)
        require(members.map { it.sourceUri }.toSet().size == members.size)
        require(members.map { it.destinationToken }.toSet().size == members.size)
        membership?.let { change ->
            require(members.size <= 1 && members.all { it.downloadId == change.id && it.logicalSourceUri == change.sourceUri })
            if (members.isEmpty()) require(direction == VisibilityDirection.HIDE && change.sourceFolder == folderId &&
                change.targetFolder != null && change.expectedReceipt?.direction == VisibilityDirection.HIDE &&
                change.expectedReceipt.folderId==change.sourceFolder && change.expectedReceipt.destinationUri==change.sourceUri)
            else if (direction == VisibilityDirection.HIDE) require(change.targetFolder == folderId)
            else require(change.sourceFolder == folderId)
        }
    }
    companion object { const val MAX_MEMBERS = 20_000 }
}

internal interface VisibilityBatchJournal {
    suspend fun read(): VisibilityBatchRecord?
    suspend fun replace(expected: VisibilityBatchRecord?, next: VisibilityBatchRecord?)
}

internal interface VisibilityBatchPorts {
    /** Confirm complete membership, rights, available storage, exact locators and no private/trash members.
     * Must not modify files. The application must exclude concurrent membership/visibility mutations. */
    suspend fun preflight(batch: VisibilityBatchRecord)
    /** Idempotent preparation only after the immutable batch is durable. */
    suspend fun prepareMembership(batch: VisibilityBatchRecord, pending:VisibilityMoveRecord?) = Unit
    /** A durable receipt plus a current exact binding/visibility check, not just a completed counter.
     * Must also work after the individual move journal has been cleared. */
    suspend fun completed(member: VisibilityMoveRecord): Boolean
    /** Idempotent final CAS: verify unchanged folder membership and all receipts, then persist mode.
     * An interruption after commit must be recognized as already committed, not toggle the flag. */
    suspend fun commitVisibility(batch: VisibilityBatchRecord)
}

internal class FolderVisibilityBatch(
    private val batches: VisibilityBatchJournal,
    private val moves: VisibilityMoveJournal,
    private val move: FolderVisibilityMove,
    private val ports: VisibilityBatchPorts,
    private val onCheckpoint: (VisibilityBatchRecord?) -> Unit = {},
) {
    suspend fun begin(batch: VisibilityBatchRecord) = gate.withLock {
        require(batch.next == 0)
        check(batches.read() == null && moves.read() == null) { "Сначала завершите предыдущий перенос" }
        ports.preflight(batch)
        batches.replace(null, batch)
        onCheckpoint(batch)
        resumeLocked()
    }
    suspend fun resume() = gate.withLock { resumeLocked() }

    private suspend fun resumeLocked() {
        var batch = batches.read() ?: run {
            check(moves.read() == null) { "Найден перенос без плана папки" }
            return
        }
        onCheckpoint(batch)
        ports.prepareMembership(batch,moves.read())
        while (batch.next < batch.members.size) {
            val member = batch.members[batch.next]
            val pending = moves.read()
            if (pending != null) {
                check(pending.copy(destinationUri = null, phase = VisibilityMovePhase.PREPARED) == member) {
                    "Незавершённый перенос не принадлежит текущему файлу папки"
                }
                move.resume()
            } else if (!ports.completed(member)) {
                move.begin(member)
            }
            check(moves.read() == null && ports.completed(member)) { "Перенос файла не подтверждён" }
            val advanced = batch.copy(next = batch.next + 1)
            batches.replace(batch, advanced)
            batch = advanced
            onCheckpoint(batch)
        }
        check(moves.read() == null)
        for (member in batch.members) check(ports.completed(member)) { "Состояние перенесённого файла изменилось" }
        ports.commitVisibility(batch)
        batches.replace(batch, null)
        onCheckpoint(null)
    }
    companion object { private val gate = Mutex() }
}

/** Non-cache batch intent, checksum protected, strictly bounded; a corrupt plan blocks mutation. */
internal class FileVisibilityBatchJournal(private val file: File) : VisibilityBatchJournal {
    override suspend fun read(): VisibilityBatchRecord? = withContext(Dispatchers.IO) { lock.withLock { readLocked() } }
    override suspend fun replace(expected: VisibilityBatchRecord?, next: VisibilityBatchRecord?) = withContext(Dispatchers.IO) {
        lock.withLock {
            check(readLocked() == expected) { "План переноса изменился" }
            val parent = requireNotNull(file.absoluteFile.parentFile)
            Files.createDirectories(parent.toPath())
            val tmp = Files.createTempFile(parent.toPath(), ".visibility-batch-", ".tmp")
            try {
                FileOutputStream(tmp.toFile()).use { out -> out.write(VisibilityBatchCodec.encode(next)); out.fd.sync() }
                Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                check(readLocked() == next)
            } finally { Files.deleteIfExists(tmp) }
        }
    }
    private fun readLocked(): VisibilityBatchRecord? {
        val path = file.toPath()
        if (Files.notExists(path)) return null
        check(Files.isRegularFile(path) && Files.size(path) in 1L..VisibilityBatchCodec.MAX_BYTES.toLong())
        return Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(64 * 1024); val out = ByteArrayOutputStream()
            while (true) {
                val n = stream.read(buffer); if (n < 0) break
                check(n <= VisibilityBatchCodec.MAX_BYTES - out.size()); out.write(buffer, 0, n)
            }
            VisibilityBatchCodec.decode(out.toByteArray())
        }
    }
    companion object { private val lock = Mutex() }
}

internal object VisibilityBatchCodec {
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAGIC = 0x4d4c4642
    fun encode(batch: VisibilityBatchRecord?): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(2); out.writeBoolean(batch != null)
            if (batch != null) {
                out.writeUTF(batch.id); out.writeUTF(batch.folderId); out.writeUTF(batch.direction.name)
                out.writeInt(batch.next); out.writeInt(batch.members.size)
                for (member in batch.members) {
                    val encoded = VisibilityJournalCodec.encode(member)
                    check(encoded.size + 4 <= MAX_BYTES - 32 - bytes.size()) { "План переноса слишком большой" }
                    out.writeInt(encoded.size); out.write(encoded)
                }
                out.writeBoolean(batch.membership != null)
                batch.membership?.let { change ->
                    out.writeUTF(change.id); out.writeBoolean(change.app)
                    fun nullable(value: String?) { out.writeBoolean(value != null); if(value != null) out.writeUTF(value) }
                    nullable(change.sourceFolder); nullable(change.targetFolder); out.writeUTF(change.sourceUri); out.writeBoolean(change.favorite)
                    val seed=change.externalSeed?.toByteArray(Charsets.UTF_8)
                    out.writeInt(seed?.size ?: -1); if(seed != null) out.write(seed)
                    val receipt=change.expectedReceipt?.let(VisibilityJournalCodec::encode)
                    out.writeInt(receipt?.size ?: -1); if(receipt != null) out.write(receipt)
                }
            }
        }
        val payload = bytes.toByteArray(); check(payload.size <= MAX_BYTES - 32)
        return payload + digest(payload)
    }
    fun decode(bytes: ByteArray): VisibilityBatchRecord? {
        check(bytes.size in 41..MAX_BYTES)
        val payload = bytes.copyOfRange(0,bytes.size - 32)
        check(MessageDigest.isEqual(digest(payload), bytes.copyOfRange(payload.size,bytes.size))) { "Повреждён план переноса" }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            check(input.readInt() == MAGIC)
            val version=input.readInt(); check(version in 1..2)
            val batch = if (!input.readBoolean()) null else {
                val id = input.readUTF(); val folder = input.readUTF(); val direction = VisibilityDirection.valueOf(input.readUTF())
                val next = input.readInt(); val count = input.readInt()
                check(count in 0..VisibilityBatchRecord.MAX_MEMBERS && next in 0..count)
                val members = List(count) {
                    val size = input.readInt(); check(size in 1..VisibilityJournalCodec.MAX_BYTES && size <= input.available())
                    requireNotNull(VisibilityJournalCodec.decode(ByteArray(size).also(input::readFully)))
                }
                val change = if(version >= 2 && input.readBoolean()) {
                    val mediaId=input.readUTF(); val app=input.readBoolean()
                    fun nullable():String?=if(input.readBoolean()) input.readUTF() else null
                    val from=nullable(); val to=nullable(); val source=input.readUTF(); val favorite=input.readBoolean()
                    fun bounded(max:Int):ByteArray? {
                        val size=input.readInt(); if(size == -1) return null
                        check(size in 1..max && size <= input.available())
                        return ByteArray(size).also(input::readFully)
                    }
                    val seed=bounded(64*1024)?.toString(Charsets.UTF_8)
                    val receipt=bounded(VisibilityJournalCodec.MAX_BYTES)?.let { requireNotNull(VisibilityJournalCodec.decode(it)) }
                    VisibilityMemberChange(mediaId,app,from,to,source,favorite,seed,receipt)
                } else null
                VisibilityBatchRecord(id, folder, direction, members, next,change)
            }
            check(input.read() == -1)
            batch
        }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
