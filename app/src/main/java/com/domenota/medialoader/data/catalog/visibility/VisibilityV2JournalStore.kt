package com.domenota.medialoader.data.catalog.visibility

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One durable v2 checkpoint for both batch and active member. Legacy v1 uses
 * batch.bin + move.bin separately and is never re-encoded by this store.
 * Each update is bounded, SHA256-protected, sync'd and atomically replaced.
 * An interrupted write leaves either the previous or the next whole state.
 */
internal data class VisibilityV2Checkpoint(
    val sequence: Long,
    val batch: VisibilityBatchRecord?,
    val move: VisibilityMoveRecord?,
) {
    init {
        require(sequence >= 0)
        VisibilityRecoveryPlanner.inspect(batch,move)
    }
}

internal object VisibilityV2CheckpointCodec {
    private const val MAGIC = 0x4d4c5632 // MLV2
    const val VERSION = 2
    const val EMPTY_BYTES = 4 + 4 + 8 + 4 + 4 + 32
    const val MAX_BYTES = VisibilityBatchCodec.MAX_BYTES + VisibilityJournalCodec.MAX_BYTES + EMPTY_BYTES

    fun encode(value: VisibilityV2Checkpoint): ByteArray {
        val batch = value.batch?.let(VisibilityBatchCodec::encode)
        val move = value.move?.let(VisibilityJournalCodec::encode)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeLong(value.sequence)
            out.writeInt(batch?.size ?: 0)
            if(batch != null) out.write(batch)
            out.writeInt(move?.size ?: 0)
            if(move != null) out.write(move)
        }
        val payload=buffer.toByteArray()
        check(payload.size + 32 <= MAX_BYTES) { "Слишком большой журнал v2" }
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    fun decode(bytes: ByteArray): VisibilityV2Checkpoint {
        check(bytes.size in EMPTY_BYTES..MAX_BYTES) { "Некорректный размер журнала v2" }
        val payload=bytes.copyOfRange(0,bytes.size-32)
        check(MessageDigest.isEqual(
            MessageDigest.getInstance("SHA-256").digest(payload),
            bytes.copyOfRange(payload.size,bytes.size))) { "Контрольная сумма журнала v2" }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            check(input.readInt()==MAGIC && input.readInt()==VERSION) { "Неизвестная версия журнала v2" }
            val sequence=input.readLong()
            fun readPayload(max:Int): ByteArray? {
                val length=input.readInt()
                check(length in 0..max && length <= input.available()) { "Некорректная запись журнала v2" }
                return if(length==0) null else ByteArray(length).also(input::readFully)
            }
            val batch=readPayload(VisibilityBatchCodec.MAX_BYTES)?.let {
                checkNotNull(VisibilityBatchCodec.decode(it)) { "Пустой batch внутри журнала v2" }
            }
            val move=readPayload(VisibilityJournalCodec.MAX_BYTES)?.let {
                checkNotNull(VisibilityJournalCodec.decode(it)) { "Пустой move внутри журнала v2" }
            }
            check(input.read()==-1) { "Лишние данные журнала v2" }
            VisibilityV2Checkpoint(sequence,batch,move)
        }
    }
}

/** Test-only crash boundaries. Production uses NONE. An injected failure does
 * not bypass CAS, fingerprint, ownership, or a single durable checkpoint. */
internal enum class VisibilityV2WriteFault { NONE, BEFORE_RENAME, AFTER_RENAME }

internal class VisibilityV2JournalStore(
    private val file:File,
    private val fault: (VisibilityV2Checkpoint)->VisibilityV2WriteFault = {VisibilityV2WriteFault.NONE},
) {
    val batches: VisibilityBatchJournal = object: VisibilityBatchJournal {
        override suspend fun read(): VisibilityBatchRecord? = snapshot().batch
        override suspend fun replace(expected:VisibilityBatchRecord?,next:VisibilityBatchRecord?) {
            update { old ->
                check(old.batch==expected) { "План v2 изменился" }
                old.copy(sequence=increment(old.sequence),batch=next)
            }
        }
    }
    val moves: VisibilityMoveJournal = object: VisibilityMoveJournal {
        override suspend fun read(): VisibilityMoveRecord? = snapshot().move
        override suspend fun replace(expected:VisibilityMoveRecord?,next:VisibilityMoveRecord?) {
            update { old ->
                check(old.move==expected) { "Файл v2 изменился" }
                old.copy(sequence=increment(old.sequence),move=next)
            }
        }
    }

    suspend fun snapshot():VisibilityV2Checkpoint=withContext(Dispatchers.IO) {
        lock.withLock { readLocked() }
    }
    private suspend fun update(transform:(VisibilityV2Checkpoint)->VisibilityV2Checkpoint) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val old=readLocked()
                val next=transform(old) // validation also rejects orphan pending files
                check(next.sequence==old.sequence+1 && next.sequence>old.sequence)
                writeLocked(next)
            }
        }
    private fun increment(old:Long):Long {
        check(old<Long.MAX_VALUE) { "Счётчик журнала v2 исчерпан" }
        return old+1
    }
    private fun readLocked():VisibilityV2Checkpoint {
        val path=file.toPath()
        if(Files.notExists(path,NOFOLLOW_LINKS)) return VisibilityV2Checkpoint(0,null,null)
        check(Files.isRegularFile(path,NOFOLLOW_LINKS)) { "Журнал v2 недоступен" }
        check(Files.size(path) in VisibilityV2CheckpointCodec.EMPTY_BYTES.toLong()..
            VisibilityV2CheckpointCodec.MAX_BYTES.toLong()) { "Некорректный размер журнала v2" }
        Files.newInputStream(path,StandardOpenOption.READ,NOFOLLOW_LINKS).use { input ->
            val output=ByteArrayOutputStream()
            val buffer=ByteArray(64*1024)
            while(true) {
                val n=input.read(buffer)
                if(n<0) break
                check(n <= VisibilityV2CheckpointCodec.MAX_BYTES-output.size()) { "Журнал v2 слишком большой" }
                output.write(buffer,0,n)
            }
            return VisibilityV2CheckpointCodec.decode(output.toByteArray())
        }
    }
    private fun writeLocked(next:VisibilityV2Checkpoint) {
        val parent=requireNotNull(file.absoluteFile.parentFile)
        Files.createDirectories(parent.toPath())
        check(Files.isDirectory(parent.toPath(),NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(file.toPath())) { "Недоступно хранилище журнала v2" }
        val temp=Files.createTempFile(parent.toPath(),".visibility-v2-",".tmp")
        try {
            FileOutputStream(temp.toFile()).use { output ->
                output.write(VisibilityV2CheckpointCodec.encode(next))
                output.fd.sync()
            }
            if(fault(next)==VisibilityV2WriteFault.BEFORE_RENAME)
                throw java.io.IOException("injected crash before rename")
            Files.move(temp,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            // No silent non-atomic fallback; sync the containing directory.
            FileChannel.open(parent.toPath(),StandardOpenOption.READ).use { it.force(true) }
            check(readLocked()==next) { "Checkpoint v2 не подтверждён" }
            if(fault(next)==VisibilityV2WriteFault.AFTER_RENAME)
                throw java.io.IOException("injected crash after rename")
        } finally { Files.deleteIfExists(temp) }
    }

    companion object {
        private val lock=Mutex()
        /** Cheap fail-closed guard. Nonempty or corrupt files are always pending.
         * Full inventory is validated later before an explicit resume. */
        fun pending(file:File):Boolean=try {
            val path=file.toPath()
            if(Files.notExists(path,NOFOLLOW_LINKS)) false
            else if(!Files.isRegularFile(path,NOFOLLOW_LINKS) ||
                Files.size(path)!=VisibilityV2CheckpointCodec.EMPTY_BYTES.toLong()) true
            else {
                Files.newInputStream(path,StandardOpenOption.READ,NOFOLLOW_LINKS).use { stream ->
                    val bytes=ByteArray(VisibilityV2CheckpointCodec.EMPTY_BYTES)
                    var offset=0
                    while(offset<bytes.size) {
                        val n=stream.read(bytes,offset,bytes.size-offset)
                        if(n<=0) break
                        offset+=n
                    }
                    offset!=bytes.size || stream.read()!=-1 ||
                        VisibilityV2CheckpointCodec.decode(bytes).let {it.batch!=null || it.move!=null}
                }
            }
        }catch(_:Exception){true}
    }
}

/** This choice does not inspect or mutate user media. Any simultaneous/unknown
 * v1 and v2 operation is refused rather than guessing which is authoritative. */
internal enum class VisibilityJournalFamily { LEGACY_V1, VERSIONED_V2 }
internal fun visibilityJournalFamily(newOperation:Boolean,v1Pending:Boolean,v2Pending:Boolean):VisibilityJournalFamily {
    check(!(v1Pending && v2Pending)) { "Одновременно обнаружены журналы v1 и v2" }
    if(newOperation) {
        check(!v1Pending && !v2Pending) { "Сначала завершите предыдущий перенос" }
        return VisibilityJournalFamily.VERSIONED_V2
    }
    check(v1Pending || v2Pending) { "Нет сохранённого переноса" }
    return if(v2Pending) VisibilityJournalFamily.VERSIONED_V2 else VisibilityJournalFamily.LEGACY_V1
}
