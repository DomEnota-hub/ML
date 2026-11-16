package com.domenota.medialoader.core.storage

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Keeps the persistent audio thumbnail and visible cards in sync with MP3 rewrites. */
object AudioThumbnailInvalidation {
    private val lock = Any()
    private val mutableRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val revisions = mutableRevisions.asStateFlow()

    fun revision(savedUri: String): Long = mutableRevisions.value[savedUri] ?: 0L

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun coverPrefix(savedUri: String) = "audio-cover-${digest(savedUri)}-"
    fun file(filesDir: File, savedUri: String): File =
        File(File(filesDir, "thumbnails"), "${digest("AUDIO|v2|$savedUri")}.jpg")

    /** Identity-bound collection covers share the original URI invalidation. */
    internal fun coverFile(filesDir: File, savedUri: String, identityToken: String): File =
        File(File(filesDir, "thumbnails"), "${coverPrefix(savedUri)}${digest(identityToken)}.jpg")

    /** Do not let a decode from before a tag rewrite recreate a stale disk cover. */
    internal fun publishIfCurrent(savedUri: String, expectedRevision: Long, publish: () -> Unit): Boolean = synchronized(lock) {
        if (revision(savedUri) != expectedRevision) false else { publish(); true }
    }

    fun invalidate(filesDir: File, savedUri: String) = synchronized(lock) {
        file(filesDir, savedUri).delete()
        val prefix = coverPrefix(savedUri)
        File(filesDir, "thumbnails").listFiles { f -> f.name.startsWith(prefix) && f.extension == "jpg" }
            ?.forEach { it.delete() }
        mutableRevisions.update { current ->
            current + (savedUri to ((current[savedUri] ?: 0L) + 1L))
        }
    }
}
