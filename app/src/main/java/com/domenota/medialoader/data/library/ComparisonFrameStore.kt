package com.domenota.medialoader.data.library

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** The URI alone is never an identity. Unknown or changed metadata cannot authorize reuse. */
internal data class ComparisonFrameKey(
    val id: String, val uri: String, val bytes: Long, val modified: Long,
    val sha: String, val epoch: Long, val stepMs: Long,
) {
    fun digest(): String {
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(1) // Feature/codec format version; bump for changed spectral extraction.
                for (value in listOf(id, uri, sha)) {
                    val data = value.toByteArray(Charsets.UTF_8)
                    output.writeInt(data.size)
                    output.write(data)
                }
                output.writeLong(this.bytes)
                output.writeLong(modified)
                output.writeLong(epoch)
                output.writeLong(stepMs)
            }
        }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun verified(track: DuplicateTrack, stamp: Pair<Long, Long?>, stepMs: Long): ComparisonFrameKey? {
            val modified = track.modified ?: return null
            if (modified <= 0 || stamp.second != modified || stamp.first != track.bytes ||
                track.bytes <= 0 || !track.sha.matches(Regex("[a-fA-F0-9]{64}")) ||
                stepMs !in setOf(256L, 512L, 1024L)) return null
            return ComparisonFrameKey(track.id, track.uri, track.bytes, modified, track.sha, track.privacyEpoch, stepMs)
        }
    }
}

/** Read-only spectral features and RMS, never PCM. All I/O on a worker.
 * The caller shares DuplicatePrivacyGuard.lock around publication/read/clear.
 */
internal class ComparisonFrameStore(private val directory: File,
    private val diskLimitBytes: Long = 32L * 1024 * 1024) {
    private val memory = object : LinkedHashMap<String, List<ComparisonFrame>>(12, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ComparisonFrame>>?) = size > 12
    }
    private fun file(key: String) = File(directory, key + ".frames")
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun valid(frame: ComparisonFrame): Boolean =
        frame.rms.isFinite() && frame.rms in 0f..1.001f &&
            frame.spectrum.size in setOf(0, 64) &&
            frame.spectrum.all { it.isFinite() && it in -1.001f..1.001f }

    @Synchronized fun read(key: ComparisonFrameKey): List<ComparisonFrame>? {
        val name = key.digest()
        memory[name]?.let { return it }
        val path = file(name)
        if (!path.isFile) return null
        val result = runCatching {
            require(path.length() in 40L..MAX_FILE_BYTES)
            val encoded = path.readBytes()
            require(encoded.size.toLong() in 40L..MAX_FILE_BYTES)
            val payload = encoded.copyOfRange(32, encoded.size)
            require(MessageDigest.isEqual(encoded.copyOfRange(0, 32), digest(payload)))
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == MAGIC)
                require(input.readLong() == key.stepMs)
                val count = input.readInt()
                require(count in 1..1800)
                List(count) {
                    val rms = input.readFloat()
                    val size = input.readInt()
                    require(size == 0 || size == 64)
                    ComparisonFrame(FloatArray(size) { input.readFloat() }, rms).also { require(valid(it)) }
                }.also { require(input.read() == -1) }
            }
        }.getOrNull()
        if (result == null) { path.delete(); return null }
        path.setLastModified(System.currentTimeMillis())
        memory[name] = result
        return result
    }

    @Synchronized fun write(key: ComparisonFrameKey, frames: List<ComparisonFrame>) {
        if (frames.size !in 1..1800 || !frames.all(::valid)) return
        val name = key.digest()
        memory[name] = frames
        // Persistence failure must not prevent a valid read-only comparison.
        runCatching {
            val payload = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeInt(MAGIC)
                    output.writeLong(key.stepMs)
                    output.writeInt(frames.size)
                    for (frame in frames) {
                        output.writeFloat(frame.rms)
                        output.writeInt(frame.spectrum.size)
                        frame.spectrum.forEach(output::writeFloat)
                    }
                }
            }.toByteArray()
            require(directory.isDirectory || directory.mkdirs())
            val temporary = File.createTempFile("frames-", ".tmp", directory)
            try {
                temporary.outputStream().buffered().use { output ->
                    output.write(digest(payload))
                    output.write(payload)
                }
                check(temporary.renameTo(file(name)))
            } finally { temporary.delete() }
            val files = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".frames") }
                .sortedBy { it.lastModified() }
            var total = files.sumOf { it.length() }
            for (oldest in files) {
                if (total <= diskLimitBytes) break
                val length = oldest.length()
                if (oldest.delete()) total -= length
            }
        }
    }

    @Synchronized fun clear(): Boolean {
        memory.clear()
        return !directory.exists() || directory.deleteRecursively()
    }

    private companion object {
        const val MAGIC = 0x4d4c4331
        const val MAX_FILE_BYTES = 512_000L
    }
}
