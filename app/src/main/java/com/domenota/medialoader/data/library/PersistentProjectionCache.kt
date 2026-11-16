package com.domenota.medialoader.data.library

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Rebuildable public metadata only. The caller must verify current access and
 * identities before publishing; a cache hit is never an authorization to open a file. */
internal class PersistentProjectionCache(context: Context, namespace: String) {
    init { require(namespace.matches(Regex("[a-z0-9-]+"))) }
    private val app = context.applicationContext
    private val directory = File(app.noBackupFilesDir, "projections-v1/$namespace")
    private fun target(key: String) = AtomicFile(File(directory, "${digest(key)}.json"))

    suspend fun read(key: String, guard: String): JSONObject? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = target(key)
                val root = file.openRead().use { input ->
                    if (input.channel.size() > MAX_BYTES) return@withLock null
                    JSONObject(input.bufferedReader().readText())
                }
                if (root.getInt("schema") != 1 || root.getString("guard") != guard) return@withLock null
                root.getJSONObject("value")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }

    suspend fun write(key: String, guard: String, value: JSONObject, valid: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bytes = JSONObject().put("schema", 1).put("guard", guard).put("value", value)
                .toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES || !valid()) return@withLock
            directory.mkdirs()
            val file = target(key)
            val output = file.startWrite()
            try { output.write(bytes); if(valid()) file.finishWrite(output) else {file.failWrite(output);return@withLock} }
            catch (e: Exception) { file.failWrite(output); throw e }
            directory.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_ENTRIES)?.forEach { AtomicFile(it).delete() }
        }
    }

    companion object {
        private val mutex = Mutex()
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_ENTRIES = 64
        suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
            mutex.withLock { check(File(context.noBackupFilesDir,"projections-v1").deleteRecursively()) { "Unable to revoke derived projection cache" } }
        }
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
