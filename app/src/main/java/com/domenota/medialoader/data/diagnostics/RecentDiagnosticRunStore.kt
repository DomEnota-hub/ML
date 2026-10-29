package com.domenota.medialoader.data.diagnostics

import android.content.Context
import android.util.AtomicFile
import com.domenota.medialoader.core.diagnostics.DiagnosticCheckSpec
import com.domenota.medialoader.core.diagnostics.DiagnosticEvidence
import com.domenota.medialoader.core.diagnostics.DiagnosticFinding
import com.domenota.medialoader.core.diagnostics.DiagnosticMode
import com.domenota.medialoader.core.diagnostics.DiagnosticOutcome
import com.domenota.medialoader.core.diagnostics.DiagnosticRun
import com.domenota.medialoader.core.diagnostics.DiagnosticRunStore
import com.domenota.medialoader.core.diagnostics.DiagnosticState
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local-only last result; restore with CURRENT registered specs to prevent stale
 * old-code names from becoming executable checks. Never stores credentials, URLs
 * or user media. A corrupted file is not silently overwritten by an empty run.
 */
class RecentDiagnosticRunStore(context: Context, private val specs: List<DiagnosticCheckSpec>)
    : DiagnosticRunStore {
    private val file = AtomicFile(File(context.applicationContext.filesDir,
        "diagnostics/latest-run-v1.json"))
    private val mutex = Mutex()

    override suspend fun save(run: DiagnosticRun): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            val bytes = DiagnosticRunCodec.encode(run)
            file.baseFile.parentFile?.mkdirs()
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
        }
    }

    override suspend fun mostRecent(): DiagnosticRun? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
                return@withContext null
            val bytes = file.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val size = input.read(chunk)
                    if (size < 0) break
                    if (output.size() + size > DiagnosticRunCodec.MAX_BYTES)
                        throw IOException("Diagnostic history file too large")
                    output.write(chunk, 0, size)
                }
                output.toByteArray()
            }
            DiagnosticRunCodec.decode(String(bytes, Charsets.UTF_8), specs)
        }
    }
}

/** Pure validation/serialization, independently unit-testable. */
object DiagnosticRunCodec {
    const val MAX_BYTES = 131_072
    private const val SCHEMA = 1

    fun encode(run: DiagnosticRun): ByteArray {
        require(run.findings.size <= 100)
        val json = JSONObject().put("schema", SCHEMA)
            .put("mode", run.mode.name)
            .put("started", run.startedAtMillis)
            .put("finished", run.finishedAtMillis)
            .put("findings", JSONArray().apply {
                run.findings.forEach { f ->
                    put(JSONObject()
                        .put("code", f.spec.code)
                        .put("state", f.state.name)
                        .put("evidence", f.evidence.name)
                        .put("checked", f.checkedAtMillis ?: JSONObject.NULL)
                        .put("duration", f.durationMillis ?: JSONObject.NULL)
                        .put("explanation", f.explanation)
                        .put("reason", f.reasonCode ?: JSONObject.NULL)
                        .put("advice", f.recommendation ?: JSONObject.NULL)
                        .put("events", JSONArray(f.relatedEventIds)))
                }
            })
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        return bytes
    }

    fun decode(raw: String, currentSpecs: List<DiagnosticCheckSpec>): DiagnosticRun {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = JSONObject(raw)
        if (root.getInt("schema") != SCHEMA) throw IOException("Unknown diagnostic format")
        val byCode = currentSpecs.associateBy { it.code }
        val rows = root.getJSONArray("findings")
        if (rows.length() > 100) throw IOException("Too many findings")
        val findings = (0 until rows.length()).map { i ->
            val f = rows.getJSONObject(i)
            val spec = byCode[f.getString("code")]
                ?: throw IOException("Diagnostic definition has changed")
            val refs = f.getJSONArray("events")
            if (refs.length() > 16) throw IOException("Too many diagnostic event links")
            val outcome = DiagnosticOutcome(
                state = DiagnosticState.valueOf(f.getString("state")),
                evidence = DiagnosticEvidence.valueOf(f.getString("evidence")),
                explanation = f.getString("explanation").also { require(it.length in 1..4000) },
                reasonCode = if (f.isNull("reason")) null else f.getString("reason"),
                recommendation = if (f.isNull("advice")) null else f.getString("advice"),
                relatedEventIds = (0 until refs.length()).map(refs::getString),
            )
            DiagnosticFinding(
                spec = spec,
                state = outcome.state,
                evidence = outcome.evidence,
                checkedAtMillis = if (f.isNull("checked")) null else f.getLong("checked"),
                durationMillis = if (f.isNull("duration")) null else f.getLong("duration"),
                explanation = outcome.explanation,
                reasonCode = outcome.reasonCode,
                recommendation = outcome.recommendation,
                relatedEventIds = outcome.relatedEventIds,
            )
        }
        return DiagnosticRun(DiagnosticMode.valueOf(root.getString("mode")),
            root.getLong("started"), root.getLong("finished"), findings)
    }
}
