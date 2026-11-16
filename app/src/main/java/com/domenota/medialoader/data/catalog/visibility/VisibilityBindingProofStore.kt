package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A large catalog snapshot must not occupy a single SQLite cursor row. Keep it
 * in one checksummed, atomic non-cache file, then compact after final cleanup. */
internal class VisibilityBindingProofStore(private val directory: File) {
    suspend fun read(id: String): String? = withContext(Dispatchers.IO) { gate.withLock { readLocked(id) } }
    suspend fun write(id: String, expected: String?, next: String) = withContext(Dispatchers.IO) {
        gate.withLock {
            check(readLocked(id) == expected) { "Журнал связей изменился" }
            val target = path(id)
            Files.createDirectories(directory.toPath())
            check(directory.canonicalFile == directory.absoluteFile)
            val payload = next.toByteArray(Charsets.UTF_8)
            check(payload.size in 1..MAX_PAYLOAD) { "Слишком большой журнал связей" }
            val temp = Files.createTempFile(directory.toPath(),".bindings-",".tmp")
            try {
                FileOutputStream(temp.toFile()).use { out ->
                    out.write(payload); out.write(digest(payload)); out.fd.sync()
                }
                Files.move(temp,target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                check(readLocked(id) == next)
            } finally { Files.deleteIfExists(temp) }
        }
    }
    private fun path(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        return File(directory,"$id.binding")
    }
    private fun readLocked(id: String): String? {
        val file = path(id); val path = file.toPath()
        if (Files.notExists(path,NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(path,NOFOLLOW_LINKS) && file.canonicalFile == file.absoluteFile)
        check(Files.size(path) in 33L..(MAX_PAYLOAD+32).toLong())
        val all = Files.newInputStream(path).use { stream ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(64*1024)
            while (true) {
                val n = stream.read(buffer); if (n < 0) break
                check(n <= MAX_PAYLOAD+32-out.size()); out.write(buffer,0,n)
            }
            out.toByteArray()
        }
        check(all.size >= 33)
        val payload = all.copyOfRange(0,all.size-32)
        check(MessageDigest.isEqual(digest(payload),all.copyOfRange(all.size-32,all.size))) { "Повреждён журнал связей" }
        return payload.toString(Charsets.UTF_8)
    }
    companion object {
        private const val MAX_PAYLOAD = 32*1024*1024
        private val gate = Mutex()
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
