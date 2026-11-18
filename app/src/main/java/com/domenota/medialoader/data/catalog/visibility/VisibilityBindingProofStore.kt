package com.domenota.medialoader.data.catalog.visibility

import com.domenota.medialoader.core.logging.AppLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Constant reason, never a user path or file name, for safe recovery diagnostics. */
internal class VisibilityProofStoreFailure(val reason: String) :
    IllegalStateException("Журнал связей недоступен: $reason")

/** The proof is durable across process death. Android's /data/user and /data/data
 * ancestors can be canonical aliases: comparing entire canonical and absolute paths
 * rejects a legitimate app-private directory on some Android versions.
 *
 * Instead validate the app-owned leaf directory and each proof file with NOFOLLOW_LINKS,
 * do not accept traversal, never follow a foreign leaf symlink and retain checksums/CAS.
 */
internal class VisibilityBindingProofStore(private val directory: File) {
    suspend fun read(id: String): String? = withContext(Dispatchers.IO) { gate.withLock { readLocked(id) } }
    suspend fun write(id: String, expected: String?, next: String) = withContext(Dispatchers.IO) {
        gate.withLock {
            if (readLocked(id) != expected) fail("CAS_CONFLICT")
            ensureDirectory(create = true)
            val target = path(id)
            val payload = next.toByteArray(Charsets.UTF_8)
            if (payload.size !in 1..MAX_PAYLOAD) fail("PAYLOAD_SIZE")
            val temp = try { Files.createTempFile(directory.toPath(), ".bindings-", ".tmp") }
                catch (_: IOException) { fail("WRITE_IO") }
            try {
                try {
                    FileOutputStream(temp.toFile()).use { out ->
                        out.write(payload); out.write(digest(payload)); out.fd.sync()
                    }
                    Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: IOException) { fail("WRITE_IO") }
                if (readLocked(id) != next) fail("WRITE_VERIFY")
            } finally { Files.deleteIfExists(temp) }
        }
    }
    private fun path(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        return File(directory, "$id.binding")
    }
    private fun ensureDirectory(create: Boolean): Boolean {
        val absolute = directory.absoluteFile.toPath()
        if (absolute != absolute.normalize()) fail("DIR_TRAVERSAL")
        val parent = directory.absoluteFile.parentFile ?: fail("DIR_PARENT")
        // The immediate parent must be an ordinary app-private directory; older
        // Android releases may still use a system-managed alias in its ancestry.
        if (!Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS)) fail("DIR_PARENT")
        if (create) {
            try { Files.createDirectories(absolute) }
            catch (_: IOException) { fail("DIR_CREATE") }
        } else if (Files.notExists(absolute, NOFOLLOW_LINKS)) return false
        if (Files.isSymbolicLink(absolute)) fail("DIR_SYMLINK")
        if (!Files.isDirectory(absolute, NOFOLLOW_LINKS)) fail("DIR_NOT_DIRECTORY")
        return true
    }
    private fun readLocked(id: String): String? {
        val file = path(id)
        if (!ensureDirectory(create = false)) return null
        val location = file.toPath()
        if (Files.notExists(location, NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(location, NOFOLLOW_LINKS)) fail("FILE_NOT_REGULAR")
        val size = try { Files.size(location) } catch (_: IOException) { fail("READ_IO") }
        if (size !in 33L..(MAX_PAYLOAD + 32).toLong()) fail("FILE_SIZE")
        // Read one bounded envelope rather than growing/copying an output buffer
        // and copying its entire payload again at every verification boundary.
        val all = try {
            Files.newInputStream(location).use { stream ->
                ByteArray(size.toInt()).also { bytes ->
                    java.io.DataInputStream(stream).readFully(bytes)
                    if (stream.read() != -1) fail("FILE_OVERSIZE")
                }
            }
        } catch (_: IOException) { fail("READ_IO") }
        val payloadSize = all.size - 32
        val actual = MessageDigest.getInstance("SHA-256").apply { update(all,0,payloadSize) }.digest()
        if (!MessageDigest.isEqual(actual,all.copyOfRange(payloadSize,all.size))) fail("CHECKSUM")
        return String(all,0,payloadSize,Charsets.UTF_8)
    }
    private fun fail(code: String): Nothing {
        val location = directory.absoluteFile.toPath()
        // No raw private path, file names, URI, proof bytes or fingerprint in logs.
        AppLog.w("FolderVisibility",
            "PROOF_GUARD code=$code dirPresent=${Files.exists(location, NOFOLLOW_LINKS)} " +
            "dirSymlink=${Files.isSymbolicLink(location)} dirRegular=${Files.isDirectory(location, NOFOLLOW_LINKS)} " +
            "canonicalAlias=${runCatching { directory.canonicalFile != directory.absoluteFile }.getOrDefault(false)}")
        throw VisibilityProofStoreFailure(code)
    }
    companion object {
        private const val MAX_PAYLOAD = 32 * 1024 * 1024
        private val gate = Mutex()
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
