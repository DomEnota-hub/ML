package com.domenota.medialoader.data.library

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Local, versioned per-public-file scan checkpoints. Never used by deletion verification. */
internal object DuplicateHashCheckpoints {
    private fun dir(context: Context) = File(context.noBackupFilesDir,"duplicate-hash-v1")
    private fun name(uri: String) = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun read(context: Context, uri: String): DuplicateTrack? = runCatching {
        val file = AtomicFile(File(dir(context), name(uri)))
        MusicDuplicateSessionStore.decodeTrack(JSONObject(file.openRead().bufferedReader().use { it.readText() }))
            .takeIf { it.uri == uri && it.modified != null }
    }.getOrNull()
    fun write(context: Context, track: DuplicateTrack) {
        runCatching {
            val directory = dir(context); check(directory.isDirectory || directory.mkdirs())
            val file = AtomicFile(File(directory,name(track.uri)))
            val stream = file.startWrite()
            try {
                stream.write(MusicDuplicateSessionStore.encodeTrack(track).toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (e: Exception) { file.failWrite(stream); throw e }
        }
    }
    fun prune(context: Context, publicUris: Set<String>) {
        val valid = publicUris.mapTo(hashSetOf(),::name)
        dir(context).listFiles()?.forEach { if (it.name.removeSuffix(".bak").removeSuffix(".new") !in valid) it.delete() }
    }
}
