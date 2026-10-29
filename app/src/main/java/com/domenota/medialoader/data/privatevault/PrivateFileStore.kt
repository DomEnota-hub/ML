package com.domenota.medialoader.data.privatevault

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

/**
 * Verified app-internal copies. Import never removes the source: callers must persist the
 * returned URI before separately calling [removePublicSource]. No content URI is granted.
 *
 * IDs are safe ASCII basenames (1–100 letters, digits, underscores or hyphens), optionally
 * followed by one 1–10 character alphanumeric extension. Missing extensions become `.bin`.
 */
class PrivateFileStore(context: Context) {
    private val context = context.applicationContext
    private val filesDirectory = this.context.filesDir.canonicalFile

    private fun directory(): File {
        val directory = File(filesDirectory, "private-media")
        require(directory.canonicalFile.parentFile == filesDirectory) { "Invalid private directory" }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create private media directory")
        }
        return directory.canonicalFile
    }

    suspend fun importUri(id: String, sourceUri: String): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(sourceUri)
        importStream(id) {
            when (uri.scheme) {
                "file" -> localFile(uri).inputStream()
                "content" -> context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open source")
                else -> throw IllegalArgumentException("Source must be a file or content URI")
            }
        }
    }

    suspend fun importFile(id: String, source: File): String = withContext(Dispatchers.IO) {
        require(source.isFile) { "Source must be a regular file" }
        importStream(id) { source.inputStream() }
    }

    private suspend fun importStream(id: String, open: () -> InputStream): String {
        require(BASENAME.matches(id)) { "Invalid private media ID" }
        val name = if ('.' in id) id else "$id.bin"
        val root = directory()
        val target = File(root, name)
        // Validate even existing targets before opening any stream or following any link.
        require(file(Uri.fromFile(target).toString()) == target) { "Invalid private target" }
        val temporary = File.createTempFile(".import-", ".tmp", root)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var length = 0L
            open().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        length += count
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            val expectedHash = digest.digest()
            val coroutineContext = currentCoroutineContext()
            if (temporary.length() != length || !hash(temporary, coroutineContext).contentEquals(expectedHash)) {
                throw IOException("Private media verification failed")
            }
            currentCoroutineContext().ensureActive()
            // Serialize publication across store instances. A committed destination is never
            // overwritten; retries reuse it only after verifying every byte by length and hash.
            synchronized(publicationLock) {
                require(file(Uri.fromFile(target).toString()) == target) { "Invalid private target" }
                if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) {
                    if (!target.isFile || target.length() != length ||
                        !hash(target, coroutineContext).contentEquals(expectedHash)) {
                        throw IOException("Private media ID already contains different bytes")
                    }
                } else if (!temporary.renameTo(target)) {
                    throw IOException("Cannot publish private media")
                }
            }
            return Uri.fromFile(target).toString()
        } finally {
            temporary.delete()
        }
    }

    /** Ownership checks include missing files so crash recovery and removal remain idempotent. */
    fun owns(uri: String): Boolean = try {
        file(uri)
        true
    } catch (_: Exception) {
        false
    }

    /** Only direct private children are accepted, with canonical checks against escaping links. */
    fun file(uri: String): File {
        val candidate = localFile(Uri.parse(uri))
        require(candidate.path.split('/').none { it == "." || it == ".." }) { "Traversal is forbidden" }
        val root = directory()
        require(BASENAME.matches(candidate.name) && candidate.parentFile?.canonicalFile == root) {
            "URI is outside private media"
        }
        val canonical = candidate.canonicalFile
        require(canonical.parentFile == root && canonical == candidate.absoluteFile) {
            "URI is not a canonical private media file"
        }
        return canonical
    }

    suspend fun remove(uri: String): Boolean = withContext(Dispatchers.IO) {
        val target = file(uri)
        if (Files.notExists(target.toPath(), NOFOLLOW_LINKS)) true
        else target.isFile && target.delete()
    }

    fun byteCount(uri: String): Long = file(uri).length()

    /**
     * Only call for the explicit public source retained by the vault record. Unknown existence
     * and denied access return false, leaving that record and its private copy available to retry.
     */
    suspend fun removePublicSource(uri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val parsed = Uri.parse(uri)
            when (parsed.scheme) {
                "file" -> {
                    val source = localFile(parsed)
                    // A caller must never accidentally remove the verified private copy.
                    if (source.canonicalFile.toPath().startsWith(filesDirectory.toPath())) false
                    else if (Files.notExists(source.toPath(), NOFOLLOW_LINKS)) true
                    else Files.isRegularFile(source.toPath(), NOFOLLOW_LINKS) && source.delete()
                }
                "content" -> {
                    val authority = parsed.authority
                    val removed = if (authority == "downloads" ||
                        authority == "com.android.providers.downloads.documents") {
                        val systemId = parsed.lastPathSegment?.toLongOrNull()
                        if (systemId != null) {
                            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                            manager != null && manager.remove(systemId) > 0
                        } else context.contentResolver.delete(parsed, null, null) > 0
                    } else context.contentResolver.delete(parsed, null, null) > 0
                    removed || contentDefinitelyMissing(parsed)
                }
                else -> false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun contentDefinitelyMissing(uri: Uri): Boolean =
        context.contentResolver.query(uri, null, null, null, null)?.use { it.count == 0 } ?: false

    private fun localFile(uri: Uri): File {
        require(uri.scheme == "file" && uri.authority.isNullOrEmpty() &&
            uri.query == null && uri.fragment == null) { "Expected a local file URI" }
        val path = requireNotNull(uri.path) { "Missing file path" }
        require(path.startsWith('/')) { "Expected an absolute file path" }
        return File(path)
    }

    private fun hash(file: File, coroutineContext: CoroutineContext): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private companion object {
        val BASENAME = Regex("[A-Za-z0-9_-]{1,100}(\\.[A-Za-z0-9]{1,10})?")
        val publicationLock = Any()
    }
}
