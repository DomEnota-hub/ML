package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Android MediaProvider writes dir.getPath() into .nomedia when marking the
 * directory clean. Empty and that EXACT system value are the only allowed
 * representations. This probe never edits, deletes, truncates, or follows links.
 * Unknown content, failed reads and concurrent changes fail closed.
 */
internal enum class HiddenNomediaKind { EMPTY, ANDROID_PATH }

internal object HiddenNomediaPolicy {
    fun classify(marker: File, ownedRoot: File): HiddenNomediaKind? {
        return try {
            val path = marker.toPath()
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attrs.isRegularFile || attrs.isSymbolicLink) return null
            if (attrs.size() == 0L) return HiddenNomediaKind.EMPTY
            val expected = ownedRoot.path.toByteArray(Charsets.UTF_8)
            if (expected.isEmpty() || expected.size > 4096 || attrs.size() != expected.size.toLong()) return null
            Files.newByteChannel(path, setOf<OpenOption>(StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
                val bytes = ByteBuffer.allocate(expected.size + 1)
                while (bytes.hasRemaining()) {
                    val count = channel.read(bytes)
                    if (count <= 0) break
                }
                if (bytes.position() != expected.size) return null
                val actual = bytes.array().copyOf(expected.size)
                if (MessageDigest.isEqual(actual, expected)) HiddenNomediaKind.ANDROID_PATH else null
            }
        } catch (_: Exception) {
            null // No trust-on-error and no mutation of a marker from another writer.
        }
    }
}
