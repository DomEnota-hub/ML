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

/** Non-cache, app-owned journal. The caller supplies a file inside noBackupFilesDir.
 * A corrupt or inaccessible record is never interpreted as 'no pending operation'. */
internal class FileVisibilityMoveJournal(private val file: File) : VisibilityMoveJournal {
    override suspend fun read(): VisibilityMoveRecord? = withContext(Dispatchers.IO) {
        gate.withLock { readLocked() }
    }

    override suspend fun replace(expected: VisibilityMoveRecord?, next: VisibilityMoveRecord?) =
        withContext(Dispatchers.IO) {
            gate.withLock {
                check(readLocked() == expected) { "Журнал переноса изменился" }
                val parent = requireNotNull(file.absoluteFile.parentFile)
                Files.createDirectories(parent.toPath())
                val temp = Files.createTempFile(parent.toPath(), ".folder-visibility-", ".tmp")
                try {
                    FileOutputStream(temp.toFile()).use { output ->
                        output.write(VisibilityJournalCodec.encode(next))
                        output.fd.sync()
                    }
                    // Do not fall back to a non-atomic replacement of recovery metadata.
                    Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                    check(readLocked() == next) { "Не удалось подтвердить журнал переноса" }
                } finally {
                    Files.deleteIfExists(temp)
                }
            }
        }

    private fun readLocked(): VisibilityMoveRecord? {
        val path = file.toPath()
        if (Files.notExists(path)) return null
        check(Files.isRegularFile(path)) { "Журнал переноса недоступен" }
        return Files.newInputStream(path).use { input ->
            val bytes = input.readNBytesBounded(VisibilityJournalCodec.MAX_BYTES)
            VisibilityJournalCodec.decode(bytes)
        }
    }

    companion object { private val gate = Mutex() }
}

private fun java.io.InputStream.readNBytesBounded(limit: Int): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        check(n <= limit - result.size()) { "Слишком большой журнал переноса" }
        result.write(buffer, 0, n)
    }
    return result.toByteArray()
}

/** Versioned, bounded payload and integrity check. Persist an empty record rather
 * than removing the file, so clearing a completed operation is atomic as well. */
internal object VisibilityJournalCodec {
    const val MAX_BYTES = 64 * 1024
    private const val MAGIC = 0x4d4c4656 // MLFV
    private const val VERSION = 3
    private const val DIGEST_BYTES = 32

    fun encode(record: VisibilityMoveRecord?): ByteArray {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeBoolean(record != null)
            if (record != null) {
                out.writeUTF(record.operationId)
                out.writeUTF(record.folderId)
                out.writeUTF(record.downloadId)
                out.writeUTF(record.direction.name)
                out.writeUTF(record.sourceUri)
                out.writeUTF(record.sourceName)
                out.writeBoolean(record.sourceModifiedMillis != null)
                record.sourceModifiedMillis?.let(out::writeLong)
                out.writeLong(record.fingerprint.bytes)
                out.writeUTF(record.fingerprint.sha256)
                out.writeUTF(record.destinationToken)
                out.writeBoolean(record.destinationUri != null)
                record.destinationUri?.let(out::writeUTF)
                out.writeUTF(record.phase.name)
                val origin = record.publicOrigin
                out.writeBoolean(origin != null)
                if (origin != null) {
                    out.writeUTF(origin.uri); out.writeUTF(origin.volume); out.writeUTF(origin.relativePath)
                    out.writeUTF(origin.name); out.writeUTF(origin.mime); out.writeUTF(origin.physicalPath)
                    out.writeLong(origin.bytes); out.writeLong(origin.modifiedSeconds); out.writeLong(origin.addedSeconds)
                    out.writeBoolean(origin.takenMillis != null); origin.takenMillis?.let(out::writeLong)
                    out.writeBoolean(origin.owner != null); origin.owner?.let(out::writeUTF)
                }
                out.writeBoolean(record.catalogSourceUri != null); record.catalogSourceUri?.let(out::writeUTF)
            }
        }
        val bytes = payload.toByteArray()
        check(bytes.size <= MAX_BYTES - DIGEST_BYTES)
        return bytes + digest(bytes)
    }

    fun decode(bytes: ByteArray): VisibilityMoveRecord? {
        check(bytes.size in (9 + DIGEST_BYTES)..MAX_BYTES) { "Неверный размер журнала" }
        val payload = bytes.copyOfRange(0, bytes.size - DIGEST_BYTES)
        check(MessageDigest.isEqual(digest(payload), bytes.copyOfRange(payload.size, bytes.size))) {
            "Контрольная сумма журнала не совпала"
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            check(input.readInt() == MAGIC) { "Неизвестный формат журнала" }
            val version = input.readInt()
            check(version in 1..VERSION) { "Неизвестный формат журнала" }
            val record = if (!input.readBoolean()) null else {
                val decoded = VisibilityMoveRecord(
                operationId = input.readUTF(), folderId = input.readUTF(), downloadId = input.readUTF(),
                direction = VisibilityDirection.valueOf(input.readUTF()), sourceUri = input.readUTF(),
                sourceName = input.readUTF(), sourceModifiedMillis = if (input.readBoolean()) input.readLong() else null,
                fingerprint = VisibilityFingerprint(input.readLong(), input.readUTF()),
                destinationToken = input.readUTF(), destinationUri = if (input.readBoolean()) input.readUTF() else null,
                phase = VisibilityMovePhase.valueOf(input.readUTF()),
                publicOrigin = if (version < 2 || !input.readBoolean()) null else VisibilityPublicOrigin(
                    input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readLong(), input.readLong(), input.readLong(),
                    if (input.readBoolean()) input.readLong() else null,
                    if (input.readBoolean()) input.readUTF() else null,
                ),
                )
                if (version >= 3 && input.readBoolean()) decoded.copy(catalogSourceUri = input.readUTF()) else decoded
            }
            check(input.read() == -1) { "Лишние данные журнала" }
            record
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
