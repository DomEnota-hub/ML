package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** A missing file or a valid encoded empty record is idle. Never parse an
 * unbounded document in a quick mutation guard, and never swallow corruption. */
internal object VisibilityPendingJournal {
    fun pending(file: File, decodeEmpty: (ByteArray) -> Boolean): Boolean = try {
        val path = file.toPath()
        if (Files.notExists(path,NOFOLLOW_LINKS)) false
        else if (!Files.isRegularFile(path,NOFOLLOW_LINKS) || file.length() != 41L) true
        else Files.newInputStream(path).use { stream ->
            // Bound even if another atomic writer replaced an empty record with a large one.
            val bytes = ByteArray(41); var offset = 0
            while (offset < bytes.size) {
                val n = stream.read(bytes,offset,bytes.size-offset)
                if (n < 0) break
                offset += n
            }
            offset != bytes.size || stream.read() != -1 || !decodeEmpty(bytes)
        }
    } catch (_: Exception) { true }
}
