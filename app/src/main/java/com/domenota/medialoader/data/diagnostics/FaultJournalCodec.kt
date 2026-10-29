package com.domenota.medialoader.data.diagnostics

import com.domenota.medialoader.core.diagnostics.DiagnosticComponent
import com.domenota.medialoader.core.diagnostics.FaultJournal
import com.domenota.medialoader.core.diagnostics.FaultRecord
import com.domenota.medialoader.core.diagnostics.FaultSeverity
import com.domenota.medialoader.core.diagnostics.FaultStatus
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** Versioned, strictly validated, platform-independent private journal serialization. */
object FaultJournalCodec {
    const val MAX_BYTES = 1_048_576
    private const val SCHEMA = 1

    fun decode(raw: String): List<FaultRecord> {
        val root = JSONObject(raw)
        if (root.getInt("schema") != SCHEMA) throw IOException("Unsupported fault journal schema")
        val data = root.getJSONArray("records")
        if (data.length() > FaultJournal.MAX_RECORDS) throw IOException("Too many fault records")
        return (0 until data.length()).map { index ->
            val row = data.getJSONObject(index)
            val refs = row.getJSONArray("eventIds")
            if (refs.length() > FaultJournal.MAX_LINKS) throw IOException("Too many event links")
            FaultRecord(
                id = row.getString("id"),
                component = DiagnosticComponent.valueOf(row.getString("component")),
                checkCode = row.getString("checkCode"),
                reasonCode = row.getString("reasonCode"),
                operationId = if (row.isNull("operationId")) null else row.getString("operationId"),
                severity = FaultSeverity.valueOf(row.getString("severity")),
                status = FaultStatus.valueOf(row.getString("status")),
                firstSeenMillis = row.getLong("firstSeenMillis"),
                lastSeenMillis = row.getLong("lastSeenMillis"),
                resolvedAtMillis = if (row.isNull("resolvedAtMillis")) null
                    else row.getLong("resolvedAtMillis"),
                repetitions = row.getInt("repetitions"),
                eventIds = (0 until refs.length()).map(refs::getString),
            )
        }.also { records ->
            if (records.map { it.id }.distinct().size != records.size)
                throw IOException("Duplicate fault journal identifiers")
        }
    }

    fun encode(records: List<FaultRecord>): ByteArray {
        require(records.size <= FaultJournal.MAX_RECORDS)
        require(records.map { it.id }.distinct().size == records.size)
        val root = JSONObject().put("schema", SCHEMA)
            .put("records", JSONArray().apply {
                records.forEach { record ->
                    put(JSONObject()
                        .put("id", record.id)
                        .put("component", record.component.name)
                        .put("checkCode", record.checkCode)
                        .put("reasonCode", record.reasonCode)
                        .put("operationId", record.operationId ?: JSONObject.NULL)
                        .put("severity", record.severity.name)
                        .put("status", record.status.name)
                        .put("firstSeenMillis", record.firstSeenMillis)
                        .put("lastSeenMillis", record.lastSeenMillis)
                        .put("resolvedAtMillis", record.resolvedAtMillis ?: JSONObject.NULL)
                        .put("repetitions", record.repetitions)
                        .put("eventIds", JSONArray(record.eventIds)))
                }
            })
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("Fault journal exceeds size limit")
        return bytes
    }
}
