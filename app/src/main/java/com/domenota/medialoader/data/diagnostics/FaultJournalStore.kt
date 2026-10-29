package com.domenota.medialoader.data.diagnostics

import android.content.Context
import android.util.AtomicFile
import com.domenota.medialoader.core.diagnostics.DiagnosticRun
import com.domenota.medialoader.core.diagnostics.FaultJournal
import com.domenota.medialoader.core.diagnostics.FaultObservation
import com.domenota.medialoader.core.diagnostics.FaultRecord
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Separate bounded private fault history. Never touches AppLog or its .md export.
 * All reads/updates are serialized and performed on Dispatchers.IO.
 * AtomicFile rolls back interrupted writes. Corrupt data are NOT silently replaced.
 */
class FaultJournalStore(context: Context) : FaultHistoryStore {
    private val file = AtomicFile(File(context.applicationContext.filesDir,
        "diagnostics/fault-journal-v1.json"))
    private val mutex = Mutex()

    override suspend fun entries(): List<FaultRecord> = mutex.withLock {
        withContext(Dispatchers.IO) { readInternal() }
    }

    override suspend fun record(run: DiagnosticRun): List<FaultRecord> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val next = FaultJournal.applyRun(readInternal(), run) { UUID.randomUUID().toString() }
            writeInternal(next)
            next
        }
    }

    override suspend fun record(observation: FaultObservation): List<FaultRecord> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val next = FaultJournal.apply(readInternal(), observation) {
                    UUID.randomUUID().toString()
                }
                writeInternal(next)
                next
            }
        }

    /** A separate, user-confirmed future UI action; never calls AppLog.clear(). */
    override suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) { file.delete() }
    }

    private fun readInternal(): List<FaultRecord> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
            return emptyList()
        // Buffered bounded read works on the app's minimum Android API level.
        val content = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > FaultJournalCodec.MAX_BYTES)
                    throw IOException("Fault journal exceeds size limit")
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
        // Invalid data are NOT replaced with an empty journal.
        return FaultJournalCodec.decode(content)
    }

    private fun writeInternal(records: List<FaultRecord>) {
        val bytes = FaultJournalCodec.encode(records)
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}

/** The UI can provide a test double; no implicit I/O when a coordinator is created. */
interface FaultHistoryStore {
    suspend fun entries(): List<FaultRecord>
    suspend fun record(run: DiagnosticRun): List<FaultRecord>
    suspend fun record(observation: FaultObservation): List<FaultRecord>
    suspend fun clear()
}
