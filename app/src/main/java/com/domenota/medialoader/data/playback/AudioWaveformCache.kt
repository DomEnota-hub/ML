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

    fun peek(source: String): FloatArray? = memory[source]

    fun get(source: String, decode: () -> FloatArray): FloatArray {
        peek(source)?.let { return it }
        val key = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(directory, "waveform_v3_$key")
        fun read(): FloatArray? = runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val count = input.readInt()
                require(count in 1..2048)
                FloatArray(count) { input.readFloat().also { require(it.isFinite()) } }
            }
        }.getOrNull()?.also { memory[source] = it }
        read()?.let { return it }
        return synchronized(locks.computeIfAbsent(source) { Any() }) {
            peek(source) ?: read() ?: decode().also { result ->
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
