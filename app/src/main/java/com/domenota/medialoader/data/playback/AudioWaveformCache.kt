package com.domenota.medialoader.data.playback

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Cache reads for one track never wait for another track's decoder. */
internal class AudioWaveformCache(private val directory: File) {
    private val memory = ConcurrentHashMap<String, FloatArray>()
    private val locks = ConcurrentHashMap<String, Any>()

    private fun savedFile(source: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "waveform_v3_$digest")
    }

    /** Works after process death without starting a decoder for an already indexed track. */
    fun peek(source: String): FloatArray? {
        memory[source]?.let { return it }
        val file = savedFile(source)
        val decoded = runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val count = input.readInt()
                require(count in 1..2048)
                FloatArray(count) { input.readFloat().also { require(it.isFinite() && it in 0f..1f) } }
            }
        }.getOrNull() ?: return null
        return memory.putIfAbsent(source, decoded) ?: decoded
    }

    /** The same MediaStore URI may now hold newly edited audio. */
    fun invalidate(source: String) {
        synchronized(locks.computeIfAbsent(source) { Any() }) {
            memory.remove(source)
            savedFile(source).delete()
        }
    }

    fun has(source: String): Boolean = peek(source) != null

    fun get(source: String, decode: () -> FloatArray): FloatArray {
        peek(source)?.let { return it }
        val file = savedFile(source)
        return synchronized(locks.computeIfAbsent(source) { Any() }) {
            peek(source) ?: decode().also { result ->
                memory[source] = result
                if (result.any { it > 0f }) runCatching {
                    directory.mkdirs()
                    val temporary = File.createTempFile("waveform-", ".tmp", directory)
                    try {
                        DataOutputStream(temporary.outputStream().buffered()).use { output ->
                            output.writeInt(result.size)
                            result.forEach(output::writeFloat)
                        }
                        check(temporary.renameTo(file))
                    } finally { temporary.delete() }
                }
            }
        }
    }
}
