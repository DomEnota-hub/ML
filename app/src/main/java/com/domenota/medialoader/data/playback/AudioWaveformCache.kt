package com.domenota.medialoader.data.playback

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Cache reads for one track never wait for another track's decoder. */
internal class AudioWaveformCache(private val directory: File) {
    // At most 128 x 2048 floats (1 MiB payload). Eviction does not remove the
    // persistent waveform: a later visit reads it without running a decoder.
    private val memory = object : LinkedHashMap<String, FloatArray>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > MAX_MEMORY_ENTRIES
    }
    private class KeyLock(var users: Int = 0)
    private val locks = HashMap<String, KeyLock>()

    private fun inMemory(source: String): FloatArray? = synchronized(memory) { memory[source] }
    private fun remember(source: String, value: FloatArray): FloatArray {
        if (value.size in 1..MAX_BUCKETS) synchronized(memory) { memory[source] = value }
        return value
    }

    /** Keep a lock only while a caller owns or awaits it. Never serialize the
     * decode of unrelated keys, or retain one lock per file in the library. */
    private fun <T> withKey(source: String, action: () -> T): T {
        val lock = synchronized(locks) { locks.getOrPut(source) { KeyLock() }.also { it.users++ } }
        return try { synchronized(lock) { action() } }
        finally { synchronized(locks) { if (--lock.users == 0) locks.remove(source) } }
    }

    private fun savedFile(source: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "waveform_v3_$digest")
    }

    private fun readLocked(source: String): FloatArray? {
        inMemory(source)?.let { return it }
        val file = savedFile(source)
        val decoded = runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val count = input.readInt()
                require(count in 1..MAX_BUCKETS)
                FloatArray(count) { input.readFloat().also { require(it.isFinite() && it in 0f..1f) } }
            }
        }.getOrNull() ?: return null
        return remember(source, decoded)
    }

    /** Works after process death without starting a decoder for an already indexed track. */
    fun peek(source: String): FloatArray? = inMemory(source) ?: withKey(source) { readLocked(source) }

    /** The same MediaStore URI may now hold newly edited audio. Disk reads and
     * publication share the same key lock, so invalidation cannot be undone by
     * a concurrent reader publishing a previously read waveform. */
    fun invalidate(source: String) = withKey(source) {
        synchronized(memory) { memory.remove(source) }
        savedFile(source).delete()
        Unit
    }

    fun has(source: String): Boolean = peek(source) != null

    fun get(source: String, decode: () -> FloatArray): FloatArray {
        inMemory(source)?.let { return it }
        return withKey(source) {
            readLocked(source) ?: decode().also { result ->
                remember(source, result)
                if (result.size in 1..MAX_BUCKETS && result.any { it > 0f }) runCatching {
                    directory.mkdirs()
                    val temporary = File.createTempFile("waveform-", ".tmp", directory)
                    try {
                        DataOutputStream(temporary.outputStream().buffered()).use { output ->
                            output.writeInt(result.size)
                            result.forEach(output::writeFloat)
                        }
                        check(temporary.renameTo(savedFile(source)))
                    } finally { temporary.delete() }
                }
            }
        }
    }

    private companion object {
        const val MAX_MEMORY_ENTRIES = 128
        const val MAX_BUCKETS = 2048
    }
}
